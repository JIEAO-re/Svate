import { z } from "zod";

// ============================================================================
// Wire schema for POST /api/mobile-agent/agent-turn (see docs/agent-loop.md §2).
//
// The device owns the loop and sends the running conversation in Gemini
// `Content[]` shape plus the tool declarations it can execute locally. The
// server is a thin function-calling model proxy: it forwards to the model with
// function calling enabled and returns the model's next turn.
//
// All wire field names are snake_case to match the contract exactly.
// ============================================================================

// Bounded identifier used for sessions and traces: ^[A-Za-z0-9_-]{1,128}$
const IdentifierSchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{1,128}$/, "must match ^[A-Za-z0-9_-]{1,128}$");

// ---------------------------------------------------------------------------
// Content parts: a discriminated union by the presence of the part's payload
// key. Each variant carries exactly one kind of payload so the server can map
// it to the matching Gemini Part without ambiguity.
// ---------------------------------------------------------------------------

const TextPartSchema = z.object({
  text: z.string(),
});

const InlineImagePartSchema = z.object({
  inline_image_base64: z.string().min(1),
  // IANA mime type for the inline image, e.g. "image/jpeg".
  mime_type: z.string().min(1),
});

const FunctionCallPartSchema = z.object({
  function_call: z.object({
    name: z.string().min(1),
    // Tool arguments as an arbitrary JSON object; defaults to {} when absent.
    args: z.record(z.string(), z.unknown()).default({}),
  }),
});

const FunctionResponsePartSchema = z.object({
  function_response: z.object({
    name: z.string().min(1),
    // Tool result as an arbitrary JSON object; defaults to {} when absent.
    response: z.record(z.string(), z.unknown()).default({}),
  }),
});

// A single conversation part. Zod cannot use a single discriminator key here
// because each variant uses a different key, so this is a plain union; each
// branch is structurally exclusive (exactly one payload key is required).
export const ContentPartSchema = z.union([
  TextPartSchema,
  InlineImagePartSchema,
  FunctionCallPartSchema,
  FunctionResponsePartSchema,
]);

export const AgentContentSchema = z.object({
  role: z.enum(["user", "model", "function"]),
  parts: z.array(ContentPartSchema).min(1),
});

// ---------------------------------------------------------------------------
// Tool declarations the device can execute. `parameters_json_schema` is any
// JSON Schema object; it is forwarded verbatim to the model, so it is received
// loosely as a record of unknown values rather than validated structurally.
// ---------------------------------------------------------------------------

export const ToolDeclarationSchema = z.object({
  name: z.string().min(1),
  description: z.string().default(""),
  parameters_json_schema: z.record(z.string(), z.unknown()).default({}),
});

export const GenerationConfigSchema = z.object({
  temperature: z.number().min(0).max(2).optional(),
  max_output_tokens: z.number().int().positive().optional(),
});

export const AgentTurnRequestSchema = z.object({
  session_id: IdentifierSchema,
  trace_id: IdentifierSchema,
  // Full system prompt text, built on the device.
  system_instruction: z.string().default(""),
  contents: z.array(AgentContentSchema).min(1),
  tools: z.array(ToolDeclarationSchema).default([]),
  generation: GenerationConfigSchema.optional(),
});

// ---------------------------------------------------------------------------
// Response: the model's next turn (text and/or tool calls).
// ---------------------------------------------------------------------------

export const ToolCallSchema = z.object({
  // Stable per-turn id, e.g. "call_0"; the device echoes it in the matching
  // function_response.
  id: z.string().min(1),
  name: z.string().min(1),
  args: z.record(z.string(), z.unknown()),
});

export const AgentAssistantSchema = z.object({
  // Narration text; null when the model returned only tool calls.
  text: z.string().nullable(),
  tool_calls: z.array(ToolCallSchema),
  // True when the model returned no tool call (turn complete) or called finish.
  finished: z.boolean(),
});

export const AgentTurnResponseSchema = z.object({
  trace_id: z.string().min(1),
  model: z.string().min(1),
  latency_ms: z.number().int().nonnegative(),
  assistant: AgentAssistantSchema,
  // Adapter metadata; only present when the request had to be degraded for the
  // active provider (e.g. an OpenAI-compatible backend without tool support).
  meta: z
    .object({
      // True when function declarations could not be delivered as tool calls
      // and the model's plain text was returned instead.
      tool_calls_unsupported: z.boolean().optional(),
      // True when one or more image inputs could not be delivered to the model.
      vision_input_missing: z.boolean().optional(),
    })
    .optional(),
});

export type ContentPart = z.infer<typeof ContentPartSchema>;
export type AgentContent = z.infer<typeof AgentContentSchema>;
export type ToolDeclaration = z.infer<typeof ToolDeclarationSchema>;
export type AgentTurnRequest = z.infer<typeof AgentTurnRequestSchema>;
export type ToolCall = z.infer<typeof ToolCallSchema>;
export type AgentAssistant = z.infer<typeof AgentAssistantSchema>;
export type AgentTurnResponse = z.infer<typeof AgentTurnResponseSchema>;
