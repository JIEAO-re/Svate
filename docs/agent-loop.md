# Agent Loop (phase: claude-code-style on-device tool loop)

This document is the single source of truth for the new **on-device, model-driven
tool loop** that runs alongside the existing fixed pipeline (`OpenClawOrchestrator`).
It mirrors the Claude Code architecture: the loop lives on the device, the model is
remote, and every tool call passes through a permission gate (ask / auto, plus an
always-on high-risk denylist).

The legacy pipeline is **not** removed. The agent loop is a new mode selectable
from the UI; the old pipeline stays as a tested fallback.

## 1. Roles

- **Device (Android)** owns the loop: it holds the running conversation, calls
  tools locally (tap/screenshot are synchronous), and only goes to the cloud to
  ask the model for the next step.
- **Cloud (Next.js)** is a thin **function-calling model proxy**: given the running
  conversation + tool declarations, it returns the model's next turn (text and/or
  tool calls). It does not drive the loop.

## 2. Wire protocol

`POST /api/mobile-agent/agent-turn` (device auth, same as next-step).

The device sends the running conversation in Gemini `Content[]` shape and the tool
declarations. The server forwards to the model with function calling enabled and
returns the model's candidate.

### Request (`AgentTurnRequest`)

```jsonc
{
  "session_id": "string",          // ^[A-Za-z0-9_-]{1,128}$
  "trace_id": "string",            // ^[A-Za-z0-9_-]{1,128}$
  "system_instruction": "string",  // full system prompt text (built on device)
  "contents": [                     // Gemini-style running conversation
    {
      "role": "user | model | function",
      "parts": [
        { "text": "string" },
        { "inline_image_base64": "string", "mime_type": "image/jpeg" },
        { "function_call": { "name": "string", "args": { } } },
        { "function_response": { "name": "string", "response": { } } }
      ]
    }
  ],
  "tools": [                        // function declarations the device can execute
    {
      "name": "tap",
      "description": "string",
      "parameters_json_schema": { }  // JSON Schema object
    }
  ],
  "generation": {                   // optional knobs
    "temperature": 0.2,
    "max_output_tokens": 2048
  }
}
```

Only the **latest** observation carries an `inline_image_base64` part. Older
observations are kept as text (foreground package + pruned UI nodes) to bound tokens.

### Response (`AgentTurnResponse`)

```jsonc
{
  "trace_id": "string",
  "model": "string",
  "latency_ms": 0,
  "assistant": {
    "text": "string | null",        // narration; may stream in a later phase
    "tool_calls": [
      { "id": "string", "name": "tap", "args": { "x": 540, "y": 1200 } }
    ],
    "finished": false                // true when the model called finish() or
                                     // returned no tool call (turn complete)
  }
}
```

Server contract notes:
- The server validates the request with Zod, builds Gemini `tools` from
  `tools[].parameters_json_schema`, calls the existing `genai-client`, and maps the
  candidate's `functionCall` parts to `assistant.tool_calls`. Text parts map to
  `assistant.text`. If there are no function calls, `finished = true`.
- Reuses device auth (`authenticateRequest`). `maxDuration = 60`.

## 3. Tool set (v1)

Each tool has: stable `name`, human description, JSON-schema params, an
`isReadOnly` flag, and a `riskClass` (see §4).

| name           | params                                   | readOnly | riskClass | impl source |
|----------------|------------------------------------------|----------|-----------|-------------|
| take_screenshot| {}                                       | yes      | safe      | AgentCaptureService |
| read_ui_tree   | {}                                       | yes      | safe      | UiTreeParser + UiNodePruner |
| tap            | {x?,y?, som_id?, selector?, target_desc?}| no       | normal    | AccessibilityMotor / AgentAccessibilityService |
| type_text      | {text, submit?}                          | no       | normal    | AgentAccessibilityService |
| swipe          | {direction} or {x1,y1,x2,y2}             | no       | low       | AgentAccessibilityService |
| scroll         | {direction}                              | no       | low       | AgentAccessibilityService |
| press_back     | {}                                       | no       | low       | AgentAccessibilityService |
| press_home     | {}                                       | no       | low       | AgentAccessibilityService |
| open_app       | {app_name?, package?}                    | no       | normal    | ExecutionModule / IntentResolver-equiv |
| launch_intent  | {action, uri?, package?}                 | no       | high      | IntentGuard (whitelist) |
| wait           | {ms}                                     | yes      | safe      | delay |
| finish         | {summary, success}                       | yes      | safe      | terminates the loop |
| ask_user       | {question}                               | yes      | safe      | pauses loop for user input |

After every **write** tool, the loop attaches a fresh observation
(`take_screenshot` + `read_ui_tree` result) as the `function_response`, so the model
always sees the effect of its action.

## 4. Permission model

Two user-selectable **modes**, switchable from a UI icon:

- **ASK** (default): every non-safe write tool asks the user before running, unless
  an allow-rule matches. Safe/read-only tools run without asking.
- **AUTO** ("放行"): write tools run without asking, *except* the high-risk
  denylist floor, which always applies.

Risk classes drive the floor:

- `safe` / `low` / `readOnly` → never blocked; in ASK mode they run without a prompt.
- `normal` → in ASK mode: ask (unless allow-rule); in AUTO mode: allow.
- `high` → **always ask, in both modes** (cannot be silently auto-run). Examples:
  `launch_intent` to a non-whitelisted action/scheme; any tap/type whose target
  text matches the hard-block keywords (pay / transfer / delete / system auth);
  system permission grants.
- Hard `deny` (never runs, both modes): actions the existing safety layer already
  hard-blocks — `EdgeSecurityGuard` injection HIGH, `AgentActionSafety`
  hard-blocked keywords on a confirmed-real target, `IntentGuard` rejects.

Decision order (first match wins):
1. `deny` floor (injection / IntentGuard reject / destructive system action) → **deny**
2. `high` riskClass → **ask** (even in AUTO)
3. allow-rule match (e.g. "always allow tap in com.foo") → **allow**
4. mode == AUTO → **allow**
5. mode == ASK and tool is `safe`/`low`/readOnly → **allow**
6. otherwise → **ask**

An "ask" pauses the loop and surfaces the existing confirm UI
(`onRequestConfirm`); the user's choice can also add a session allow-rule
("always allow this") to reduce future prompts.

## 5. Termination

The loop ends when: the model calls `finish`, returns no tool call, hits
`maxTurns`, exhausts a token/step budget, the user stops it, or a deny floor
plus repeated failures trip the failure budget.

## 6. Kotlin layout (new package, coexists with the old pipeline)

```
app/src/main/java/com/immersive/ui/agent/loop/
  PhoneTool.kt            // tool abstraction + ToolResult + RiskClass
  ToolRegistry.kt         // builds the v1 tool list, JSON-schema declarations
  tools/                  // one file per tool, wrapping existing capabilities
  AgentLoop.kt            // the coroutine query loop (suspend, emits events via Flow)
  AgentTurnClient.kt      // POSTs /agent-turn, maps wire <-> domain
  PermissionGate.kt       // modes + rules + high-risk floor (reuses AgentActionSafety/IntentGuard)
  AgentLoopState.kt       // conversation content list + budgets + mode
```

The UI exposes a mode toggle (ASK / AUTO) and routes the new "agent loop" mode to
`AgentLoop`; the existing pipeline mode is unchanged.

## 7. Known gaps (P2, not yet done)

The loop compiles and its pure logic is unit-tested, but it has **not** been
validated end to end on a real device. Before that happens:

- **MediaProjection is not wired into loop mode.** `AgentCaptureService` needs an
  active projection (a user consent dialog) to capture frames; loop mode currently
  skips that flow, so `take_screenshot` and observation images return nothing and
  the agent runs **UI-tree-only** (`read_ui_tree` still works — nodes carry pixel
  bounds, so `tap {x,y}` is viable). Wiring the projection consent into loop startup
  is the first P2 task; until then the system prompt should not promise screenshots.
- **Streaming narration** is deferred; the proxy returns one response per turn.
- **Gemini `parametersJsonSchema`** support depends on the installed `@google/genai`
  version exposing that field (it type-checks today); verify against the live API.
- **Coordinate/SoM tap screening** resolves the targeted node's text for hard-keyword
  screening, but a node with no text (icon-only button) cannot be screened by keyword
  and relies on the injection/IntentGuard floor plus ASK mode.
- **reviewer** is not consulted in the loop; moving it to an async safety advisor is a
  later phase.
