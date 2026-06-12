import type { Content, Part } from "@google/genai";
import { getGenAIClient, resolveModelWithFallback } from "@/lib/mobile-agent/genai-client";
import { OPENAI_COMPAT_ENABLED, PLANNER_MODEL } from "@/lib/mobile-agent/env";
import type {
  AgentContent,
  AgentTurnRequest,
  AgentTurnResponse,
  ContentPart,
  ToolCall,
  ToolDeclaration,
} from "@/lib/schemas/agent-turn";

// ============================================================================
// Core of the agent loop's cloud half (see docs/agent-loop.md §2).
//
// `runAgentTurn` is a thin function-calling model proxy: it maps the wire
// request into Gemini `Content[]` + `tools`, calls the shared genai-client with
// function calling enabled, and maps the candidate's functionCall parts back to
// `assistant.tool_calls`. When the active provider is an OpenAI-compatible
// backend that cannot execute tool calls, it degrades to returning the model's
// plain text with `finished = true` plus a meta marker, never a 500.
// ============================================================================

// Fallback model used when the configured planner model cannot be resolved.
const FALLBACK_MODEL = "gemini-2.5-flash";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

// ---------------------------------------------------------------------------
// Wire content -> Gemini Content[] mapping.
// ---------------------------------------------------------------------------

function mapPartToGemini(part: ContentPart): Part | null {
  if ("text" in part) {
    return { text: part.text };
  }
  if ("inline_image_base64" in part) {
    return {
      inlineData: {
        data: part.inline_image_base64,
        mimeType: part.mime_type,
      },
    };
  }
  if ("function_call" in part) {
    return {
      functionCall: {
        name: part.function_call.name,
        args: part.function_call.args,
      },
    };
  }
  if ("function_response" in part) {
    return {
      functionResponse: {
        name: part.function_response.name,
        response: part.function_response.response,
      },
    };
  }
  return null;
}

function mapContentToGemini(content: AgentContent): Content {
  const parts: Part[] = [];
  for (const part of content.parts) {
    const mapped = mapPartToGemini(part);
    if (mapped) parts.push(mapped);
  }
  // Gemini's Content.role accepts only "user" and "model". The wire protocol
  // uses a semantic "function" role for tool-result turns; Gemini expects those
  // functionResponse parts under role "user". Without this remap every turn
  // after the first tool call would be rejected as an invalid role.
  const role = content.role === "function" ? "user" : content.role;
  return { role, parts };
}

// ---------------------------------------------------------------------------
// Wire tools -> Gemini tools[{ functionDeclarations }] mapping. The JSON Schema
// from `parameters_json_schema` is forwarded verbatim via parametersJsonSchema.
// ---------------------------------------------------------------------------

type GeminiFunctionDeclaration = {
  name: string;
  description: string;
  parametersJsonSchema: Record<string, unknown>;
};

function mapToolsToGemini(
  tools: ToolDeclaration[],
): Array<{ functionDeclarations: GeminiFunctionDeclaration[] }> | undefined {
  if (tools.length === 0) return undefined;
  return [
    {
      functionDeclarations: tools.map((tool) => ({
        name: tool.name,
        description: tool.description,
        parametersJsonSchema: tool.parameters_json_schema,
      })),
    },
  ];
}

// ---------------------------------------------------------------------------
// Wire tools -> OpenAI tools mapping (best effort). Used only on the
// OpenAI-compatible degraded path so the mapping is still attempted even when
// the backend ultimately ignores it.
// ---------------------------------------------------------------------------

type OpenAITool = {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
};

function mapToolsToOpenAI(tools: ToolDeclaration[]): OpenAITool[] {
  return tools.map((tool) => ({
    type: "function",
    function: {
      name: tool.name,
      description: tool.description,
      parameters: tool.parameters_json_schema,
    },
  }));
}

// ---------------------------------------------------------------------------
// Candidate parsing: split a candidate's parts into text + tool calls.
// ---------------------------------------------------------------------------

type ParsedCandidate = {
  text: string | null;
  toolCalls: ToolCall[];
};

function parseCandidate(parts: Array<{ text?: string; functionCall?: { name?: string; args?: Record<string, unknown> } }>): ParsedCandidate {
  const textChunks: string[] = [];
  const toolCalls: ToolCall[] = [];

  for (const part of parts) {
    if (part.functionCall && typeof part.functionCall.name === "string" && part.functionCall.name) {
      // Generate a stable id from the call's position so the device can match
      // the eventual function_response back to this call.
      toolCalls.push({
        id: `call_${toolCalls.length}`,
        name: part.functionCall.name,
        args: isRecord(part.functionCall.args) ? part.functionCall.args : {},
      });
      continue;
    }
    if (typeof part.text === "string" && part.text.length > 0) {
      textChunks.push(part.text);
    }
  }

  return {
    text: textChunks.length > 0 ? textChunks.join("") : null,
    toolCalls,
  };
}

// ---------------------------------------------------------------------------
// Entry point.
// ---------------------------------------------------------------------------

export async function runAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  const started = Date.now();
  const ai = getGenAIClient();
  const resolvedModel = await resolveModelWithFallback(PLANNER_MODEL, [FALLBACK_MODEL]);

  const contents = request.contents.map(mapContentToGemini);
  const systemInstruction = request.system_instruction.trim()
    ? request.system_instruction
    : undefined;

  // OpenAI-compatible degraded path: the shared adapter flattens the
  // conversation into a single user message and does not execute tool calls.
  // Map the declarations to OpenAI tool format on a best-effort basis, then
  // return the model's plain text with finished = true and a meta marker.
  if (OPENAI_COMPAT_ENABLED) {
    // Build the mapping even though the adapter ignores it, to honor the
    // best-effort contract and keep the shape ready for a future tool-capable
    // backend.
    void mapToolsToOpenAI(request.tools);

    const response = await ai.models.generateContent({
      model: resolvedModel,
      contents,
      config: {
        ...(systemInstruction ? { systemInstruction } : {}),
        ...(request.generation?.temperature !== undefined
          ? { temperature: request.generation.temperature }
          : {}),
        ...(request.generation?.max_output_tokens !== undefined
          ? { maxOutputTokens: request.generation.max_output_tokens }
          : {}),
      },
    });

    const text = response.text && response.text.length > 0 ? response.text : null;
    return {
      trace_id: request.trace_id,
      model: resolvedModel,
      latency_ms: Date.now() - started,
      assistant: {
        text,
        tool_calls: [],
        finished: true,
      },
      meta: {
        tool_calls_unsupported: true,
        ...(response.meta?.vision_input_missing ? { vision_input_missing: true } : {}),
      },
    };
  }

  // Gemini path: forward the conversation with function calling enabled and map
  // the candidate's functionCall parts to assistant.tool_calls.
  const geminiTools = mapToolsToGemini(request.tools);
  const response = await ai.models.generateContent({
    model: resolvedModel,
    contents,
    config: {
      ...(systemInstruction ? { systemInstruction } : {}),
      ...(request.generation?.temperature !== undefined
        ? { temperature: request.generation.temperature }
        : {}),
      ...(request.generation?.max_output_tokens !== undefined
        ? { maxOutputTokens: request.generation.max_output_tokens }
        : {}),
      ...(geminiTools ? { tools: geminiTools } : {}),
    },
  });

  const candidate = response.candidates?.[0];
  const candidateParts = candidate?.content?.parts ?? [];
  const parsed = parseCandidate(candidateParts);

  // A MAX_TOKENS finish means the model's output was cut off mid-stream. Such a
  // turn must not be reported as a completion: any tool calls in it may be
  // partial, and an empty turn is a failed turn, not a success. Mark it via
  // meta.truncated and force finished = false so the device treats it as a
  // failed turn (checkBudgetAfterFailure) rather than emitting success.
  const truncated = candidate?.finishReason === "MAX_TOKENS";

  return {
    trace_id: request.trace_id,
    model: resolvedModel,
    latency_ms: Date.now() - started,
    assistant: {
      text: parsed.text,
      tool_calls: parsed.toolCalls,
      // No function call means the turn is complete, unless the candidate was
      // truncated (MAX_TOKENS), in which case the turn is never a completion.
      finished: truncated ? false : parsed.toolCalls.length === 0,
    },
    ...(truncated || response.meta?.vision_input_missing
      ? {
          meta: {
            ...(truncated ? { truncated: true } : {}),
            ...(response.meta?.vision_input_missing
              ? { vision_input_missing: true }
              : {}),
          },
        }
      : {}),
  };
}
