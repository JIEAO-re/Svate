import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const ORIGINAL_ENV = { ...process.env };

function resetEnv() {
  for (const key of Object.keys(process.env)) {
    if (!(key in ORIGINAL_ENV)) {
      delete process.env[key];
    }
  }
  Object.assign(process.env, ORIGINAL_ENV);
}

// process.env.NODE_ENV is typed read-only in Next.js; tests need to override it.
function setNodeEnv(value: string) {
  (process.env as Record<string, string | undefined>).NODE_ENV = value;
}

// Enable the loopback dev bypass so authenticateRequest passes without a token.
function enableDevAuthBypass() {
  setNodeEnv("development");
  process.env.SKIP_AUTH_DEV = "true";
  process.env.GEMINI_API_KEY = "test-key";
}

type GenerateContentMock = ReturnType<typeof vi.fn>;

// Mock the shared genai-client so no real model call is made. The mock captures
// the params the route forwards so assertions can inspect the Gemini tools.
function mockGenAiClient(generateContent: GenerateContentMock) {
  vi.doMock("@/lib/mobile-agent/genai-client", () => ({
    getGenAIClient: () => ({
      models: { generateContent },
    }),
    resolveModelWithFallback: vi.fn().mockResolvedValue("gemini-2.5-flash"),
  }));
}

function buildRequestBody(overrides: Record<string, unknown> = {}) {
  return {
    session_id: "sess_1",
    trace_id: "trace_1",
    system_instruction: "You are a phone agent.",
    contents: [
      {
        role: "user",
        parts: [{ text: "Open settings" }],
      },
    ],
    tools: [
      {
        name: "tap",
        description: "Tap a point on the screen.",
        parameters_json_schema: {
          type: "object",
          properties: {
            x: { type: "number" },
            y: { type: "number" },
          },
          required: ["x", "y"],
        },
      },
    ],
    ...overrides,
  };
}

async function postAgentTurn(body: unknown, headers: Record<string, string> = {}) {
  const { POST } = await import("@/app/api/mobile-agent/agent-turn/route");
  return POST(
    new Request("http://localhost/api/mobile-agent/agent-turn", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...headers,
      },
      body: JSON.stringify(body),
    }),
  );
}

describe("POST /api/mobile-agent/agent-turn", () => {
  beforeEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("rejects unauthenticated requests with 401", async () => {
    setNodeEnv("development");
    process.env.SKIP_AUTH_DEV = "false";

    const response = await postAgentTurn(buildRequestBody());

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      error: "authentication_failed",
    });
  });

  it("rejects requests that fail zod validation with 400", async () => {
    enableDevAuthBypass();
    const generateContent = vi.fn();
    mockGenAiClient(generateContent);

    // trace_id violates ^[A-Za-z0-9_-]{1,128}$ via the slash character.
    const response = await postAgentTurn(
      buildRequestBody({ trace_id: "bad/trace" }),
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      error: "invalid_agent_turn_request",
    });
    expect(generateContent).not.toHaveBeenCalled();
  });

  it("maps a model functionCall to assistant.tool_calls", async () => {
    enableDevAuthBypass();
    const generateContent = vi.fn().mockResolvedValue({
      candidates: [
        {
          content: {
            parts: [
              {
                functionCall: {
                  name: "tap",
                  args: { x: 540, y: 1200 },
                },
              },
            ],
          },
        },
      ],
    });
    mockGenAiClient(generateContent);

    const response = await postAgentTurn(buildRequestBody());

    expect(response.status).toBe(200);
    const json = await response.json();
    expect(json).toMatchObject({
      success: true,
      trace_id: "trace_1",
      model: "gemini-2.5-flash",
      assistant: {
        text: null,
        tool_calls: [
          { id: "call_0", name: "tap", args: { x: 540, y: 1200 } },
        ],
        finished: false,
      },
    });
    expect(typeof json.latency_ms).toBe("number");
  });

  it("maps a pure text candidate to finished = true with no tool calls", async () => {
    enableDevAuthBypass();
    const generateContent = vi.fn().mockResolvedValue({
      candidates: [
        {
          content: {
            parts: [{ text: "Task complete." }],
          },
        },
      ],
    });
    mockGenAiClient(generateContent);

    const response = await postAgentTurn(buildRequestBody());

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      success: true,
      assistant: {
        text: "Task complete.",
        tool_calls: [],
        finished: true,
      },
    });
  });

  it("forwards each tool's JSON schema into Gemini functionDeclarations", async () => {
    enableDevAuthBypass();
    const generateContent = vi.fn().mockResolvedValue({
      candidates: [{ content: { parts: [{ text: "ok" }] } }],
    });
    mockGenAiClient(generateContent);

    await postAgentTurn(buildRequestBody());

    expect(generateContent).toHaveBeenCalledOnce();
    const params = generateContent.mock.calls[0][0];
    expect(params.config.systemInstruction).toBe("You are a phone agent.");
    expect(params.config.tools).toEqual([
      {
        functionDeclarations: [
          {
            name: "tap",
            description: "Tap a point on the screen.",
            parametersJsonSchema: {
              type: "object",
              properties: {
                x: { type: "number" },
                y: { type: "number" },
              },
              required: ["x", "y"],
            },
          },
        ],
      },
    ]);
  });

  it("remaps the wire 'function' role to Gemini's 'user' role", async () => {
    enableDevAuthBypass();
    const generateContent = vi.fn().mockResolvedValue({
      candidates: [{ content: { parts: [{ text: "ok" }] } }],
    });
    mockGenAiClient(generateContent);

    // A tool-result turn uses role "function" on the wire; Gemini only accepts
    // "user"/"model", so the proxy must remap it or the turn is rejected.
    await postAgentTurn(
      buildRequestBody({
        contents: [
          { role: "user", parts: [{ text: "Open settings" }] },
          {
            role: "model",
            parts: [{ function_call: { name: "tap", args: { x: 1, y: 2 } } }],
          },
          {
            role: "function",
            parts: [{ function_response: { name: "tap", response: { ok: true } } }],
          },
        ],
      }),
    );

    expect(generateContent).toHaveBeenCalledOnce();
    const params = generateContent.mock.calls[0][0];
    const roles = params.contents.map((c: { role: string }) => c.role);
    expect(roles).toEqual(["user", "model", "user"]);
    // The functionResponse part itself is preserved under the remapped role.
    expect(params.contents[2].parts[0]).toHaveProperty("functionResponse");
  });

  it("marks meta.truncated and finished = false when the candidate hit MAX_TOKENS", async () => {
    enableDevAuthBypass();
    // A MAX_TOKENS finishReason means the model's output was cut off. The proxy
    // must flag meta.truncated and never report the turn as finished, even
    // though no tool call was parsed.
    const generateContent = vi.fn().mockResolvedValue({
      candidates: [
        {
          content: {
            parts: [{ text: "Partial narration that got cut" }],
          },
          finishReason: "MAX_TOKENS",
        },
      ],
    });
    mockGenAiClient(generateContent);

    const response = await postAgentTurn(buildRequestBody());

    expect(response.status).toBe(200);
    const json = await response.json();
    expect(json).toMatchObject({
      success: true,
      assistant: {
        text: "Partial narration that got cut",
        tool_calls: [],
        finished: false,
      },
      meta: { truncated: true },
    });
  });

  it("does not set meta.truncated when the candidate finished with STOP", async () => {
    enableDevAuthBypass();
    const generateContent = vi.fn().mockResolvedValue({
      candidates: [
        {
          content: {
            parts: [{ text: "Task complete." }],
          },
          finishReason: "STOP",
        },
      ],
    });
    mockGenAiClient(generateContent);

    const response = await postAgentTurn(buildRequestBody());

    expect(response.status).toBe(200);
    const json = await response.json();
    expect(json).toMatchObject({
      success: true,
      assistant: {
        text: "Task complete.",
        tool_calls: [],
        finished: true,
      },
    });
    // A normal STOP finish must not carry a truncated marker.
    expect(json.meta?.truncated).toBeUndefined();
  });

  it("maps OpenAI tool_calls to assistant.tool_calls on an OpenAI-compatible backend", async () => {
    enableDevAuthBypass();
    process.env.OPENAI_COMPAT_ENABLED = "true";
    process.env.OPENAI_COMPAT_BASE_URL = "https://compat.example/v1";
    process.env.OPENAI_COMPAT_API_KEY = "compat-key";
    // resolveModelWithFallback is mocked so the /models lookup is skipped; the
    // OpenAI path itself does not call getGenAIClient.
    mockGenAiClient(vi.fn());

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        choices: [
          {
            finish_reason: "tool_calls",
            message: {
              content: "Tapping the settings icon.",
              tool_calls: [
                { id: "call_abc", type: "function", function: { name: "tap", arguments: '{"x":100,"y":200}' } },
              ],
            },
          },
        ],
      }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const response = await postAgentTurn(buildRequestBody());

    expect(response.status).toBe(200);
    const json = await response.json();
    expect(json).toMatchObject({
      success: true,
      assistant: {
        text: "Tapping the settings icon.",
        tool_calls: [{ id: "call_abc", name: "tap", args: { x: 100, y: 200 } }],
        finished: false,
      },
    });

    // The request must hit chat/completions with OpenAI-format tools, a system
    // message, and Bearer auth.
    const [url, init] = fetchMock.mock.calls[0] as [string, { body: string; headers: Record<string, string> }];
    expect(url).toBe("https://compat.example/v1/chat/completions");
    const sent = JSON.parse(init.body);
    expect(sent.tools[0].function.name).toBe("tap");
    expect(sent.tool_choice).toBe("auto");
    expect(sent.messages[0].role).toBe("system");
    expect(init.headers.Authorization).toBe("Bearer compat-key");
  });

  it("finishes when the OpenAI-compatible backend returns plain text", async () => {
    enableDevAuthBypass();
    process.env.OPENAI_COMPAT_ENABLED = "true";
    process.env.OPENAI_COMPAT_BASE_URL = "https://compat.example/v1";
    process.env.OPENAI_COMPAT_API_KEY = "compat-key";
    mockGenAiClient(vi.fn());

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          choices: [{ finish_reason: "stop", message: { content: "All done." } }],
        }),
      }),
    );

    const response = await postAgentTurn(buildRequestBody());

    expect(response.status).toBe(200);
    const json = await response.json();
    expect(json).toMatchObject({
      success: true,
      assistant: { text: "All done.", tool_calls: [], finished: true },
    });
    expect(json.meta?.tool_calls_unsupported).toBeUndefined();
  });
});
