import type { Content, Part } from "@google/genai";
import { getGenAIClient, resolveModelWithFallback } from "@/lib/mobile-agent/genai-client";
import {
  OPENAI_COMPAT_ENABLED,
  OPENAI_COMPAT_BASE_URL,
  OPENAI_COMPAT_API_KEY,
  PLANNER_MODEL,
} from "@/lib/mobile-agent/env";
import { AgentTurnResponseSchema } from "@/lib/schemas/agent-turn";
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
// OpenAI-compatible path with real tool calling.
//
// Unlike the shared genai-client adapter (which flattens the conversation into a
// single user message and cannot execute tools), this builds proper multi-turn
// OpenAI Chat Completions `messages` — preserving roles, mapping function_call
// parts to assistant `tool_calls` and function_response parts to `tool` messages
// — and sends the tool declarations, so an OpenAI-compatible gateway (base_url +
// api key) drives the agent loop with genuine function calling.
// ---------------------------------------------------------------------------

type OpenAIChatMessage =
  | { role: "system"; content: string }
  | {
      role: "user";
      content: string | Array<{ type: "text"; text: string } | { type: "image_url"; image_url: { url: string } }>;
    }
  | {
      role: "assistant";
      content: string | null;
      tool_calls?: Array<{ id: string; type: "function"; function: { name: string; arguments: string } }>;
    }
  | { role: "tool"; tool_call_id: string; content: string };

/**
 * Convert the wire Gemini-style Content[] into OpenAI Chat Completions messages.
 * The device sends each function_call as its own `model` turn immediately followed
 * by the matching `function` turn (agent-loop.md §2), so tool-call ids are paired
 * positionally via a FIFO queue.
 */
function contentsToOpenAIMessages(
  contents: AgentContent[],
  systemInstruction: string,
): OpenAIChatMessage[] {
  const messages: OpenAIChatMessage[] = [];
  if (systemInstruction.trim()) {
    messages.push({ role: "system", content: systemInstruction });
  }

  const pendingToolCallIds: string[] = [];
  let toolCallCounter = 0;

  for (const content of contents) {
    if (content.role === "function") {
      for (const part of content.parts) {
        if ("function_response" in part) {
          const toolCallId = pendingToolCallIds.shift() ?? `call_${toolCallCounter++}`;
          messages.push({
            role: "tool",
            tool_call_id: toolCallId,
            content: JSON.stringify(part.function_response.response ?? {}),
          });
        }
      }
      continue;
    }

    if (content.role === "model") {
      const toolCalls: Array<{ id: string; type: "function"; function: { name: string; arguments: string } }> = [];
      const textChunks: string[] = [];
      for (const part of content.parts) {
        if ("function_call" in part) {
          const id = `call_${toolCallCounter++}`;
          pendingToolCallIds.push(id);
          toolCalls.push({
            id,
            type: "function",
            function: { name: part.function_call.name, arguments: JSON.stringify(part.function_call.args ?? {}) },
          });
        } else if ("text" in part) {
          textChunks.push(part.text);
        }
      }
      if (toolCalls.length > 0) {
        messages.push({ role: "assistant", content: textChunks.join("") || null, tool_calls: toolCalls });
      } else {
        messages.push({ role: "assistant", content: textChunks.join("") });
      }
      continue;
    }

    // role === "user": text and/or inline images.
    const parts: Array<{ type: "text"; text: string } | { type: "image_url"; image_url: { url: string } }> = [];
    for (const part of content.parts) {
      if ("text" in part) {
        parts.push({ type: "text", text: part.text });
      } else if ("inline_image_base64" in part) {
        parts.push({
          type: "image_url",
          image_url: { url: `data:${part.mime_type};base64,${part.inline_image_base64}` },
        });
      }
    }
    if (parts.length === 1 && parts[0].type === "text") {
      messages.push({ role: "user", content: parts[0].text });
    } else {
      messages.push({ role: "user", content: parts });
    }
  }

  return messages;
}

async function runAgentTurnViaOpenAI(
  request: AgentTurnRequest,
  resolvedModel: string,
  started: number,
): Promise<AgentTurnResponse> {
  const messages = contentsToOpenAIMessages(request.contents, request.system_instruction);
  const tools = request.tools.length > 0 ? mapToolsToOpenAI(request.tools) : undefined;

  const body: Record<string, unknown> = {
    model: resolvedModel,
    messages,
    temperature: request.generation?.temperature ?? 0.2,
    ...(tools ? { tools, tool_choice: "auto" } : {}),
    ...(request.generation?.max_output_tokens !== undefined
      ? { max_tokens: request.generation.max_output_tokens }
      : {}),
  };

  const response = await fetch(`${OPENAI_COMPAT_BASE_URL}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${OPENAI_COMPAT_API_KEY}`,
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`[openai-compat] agent-turn failed: HTTP ${response.status} | ${text}`);
  }

  const json: unknown = await response.json();
  const choice = isRecord(json) && Array.isArray(json.choices) ? json.choices[0] : undefined;
  const message = isRecord(choice) && isRecord(choice.message) ? choice.message : {};
  const finishReason = isRecord(choice) && typeof choice.finish_reason === "string" ? choice.finish_reason : "";

  const text =
    typeof message.content === "string" && message.content.length > 0 ? message.content : null;

  const rawToolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
  const toolCalls: ToolCall[] = [];
  rawToolCalls.forEach((tc, index) => {
    if (!isRecord(tc) || !isRecord(tc.function) || typeof tc.function.name !== "string" || !tc.function.name) {
      return;
    }
    let args: Record<string, unknown> = {};
    const rawArgs = tc.function.arguments;
    if (typeof rawArgs === "string" && rawArgs.trim()) {
      try {
        const parsed = JSON.parse(rawArgs);
        if (isRecord(parsed)) args = parsed;
      } catch {
        args = {};
      }
    } else if (isRecord(rawArgs)) {
      args = rawArgs as Record<string, unknown>;
    }
    toolCalls.push({
      id: typeof tc.id === "string" && tc.id ? tc.id : `call_${index}`,
      name: tc.function.name,
      args,
    });
  });

  // A "length" finish means the output was cut off; treat it as a failed turn,
  // never a completion (mirrors the Gemini MAX_TOKENS handling).
  const truncated = finishReason === "length";

  return {
    trace_id: request.trace_id,
    model: resolvedModel,
    latency_ms: Date.now() - started,
    assistant: {
      text,
      tool_calls: toolCalls,
      finished: truncated ? false : toolCalls.length === 0,
    },
    ...(truncated ? { meta: { truncated: true } } : {}),
  };
}

// ---------------------------------------------------------------------------
// Entry point.
// ---------------------------------------------------------------------------

export async function runAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  const started = Date.now();
  const resolvedModel = await resolveModelWithFallback(PLANNER_MODEL, [FALLBACK_MODEL]);

  // OpenAI-compatible gateway (base_url + api key): drive the loop with genuine
  // function calling over Chat Completions. Otherwise use the Gemini path.
  const result = OPENAI_COMPAT_ENABLED
    ? await runAgentTurnViaOpenAI(request, resolvedModel, started)
    : await runAgentTurnViaGemini(request, resolvedModel, started);

  // Validate the outgoing turn against the wire contract before it leaves the
  // server, mirroring the pipeline's NextStepResponseSchema.parse so both model
  // proxy paths enforce their response shape rather than returning it unchecked.
  return AgentTurnResponseSchema.parse(result);
}

// Gemini path: forward the conversation with function calling enabled and map
// the candidate's functionCall parts to assistant.tool_calls.
async function runAgentTurnViaGemini(
  request: AgentTurnRequest,
  resolvedModel: string,
  started: number,
): Promise<AgentTurnResponse> {
  const ai = getGenAIClient();
  const contents = request.contents.map(mapContentToGemini);
  const systemInstruction = request.system_instruction.trim()
    ? request.system_instruction
    : undefined;
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
