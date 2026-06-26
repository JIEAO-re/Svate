import { NextResponse } from "next/server";
import { z } from "zod";
import { getGenAIClient } from "@/lib/mobile-agent/genai-client";
import { LEGACY_DEMO_MODEL, GENAI_CLIENT_ENABLED } from "@/lib/mobile-agent/env";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";
import { rateLimitGuard } from "@/lib/mobile-agent/rate-limit";

const ChatMessageSchema = z.object({
  role: z.enum(["user", "assistant"]),
  content: z.string().min(1).max(1000),
});

// One uploaded attachment. Images carry raw base64 (no data: prefix) and are
// sent to the model as vision input; text-like files carry extracted UTF-8
// text; other binaries are referenced by name only (the model can't read them
// through the OpenAI-compatible image path).
const AttachmentSchema = z.object({
  kind: z.enum(["image", "file"]),
  name: z.string().min(1).max(200),
  mime_type: z.string().min(1).max(150),
  size_bytes: z.number().int().nonnegative().max(8_000_000),
  data_base64: z.string().max(11_000_000).optional(),
  text_content: z.string().max(12_000).optional(),
});

const ChatRequestSchema = z.object({
  messages: z.array(ChatMessageSchema).min(1).max(40),
  attachments: z.array(AttachmentSchema).max(4).default([]),
});

const AppCandidateSchema = z.object({
  app_name: z.string().min(1),
  package_name: z.string().min(1),
  reason: z.string().min(1),
});

const ChatGoalResponseSchema = z.object({
  reply: z.string().min(1),
  inferred_goal: z.string().min(1),
  target_app_name: z.string(),
  ready_to_start: z.boolean(),
  task_mode: z.enum(["GENERAL", "SEARCH", "RESEARCH", "HOMEWORK"]).default("GENERAL"),
  search_query: z.string().default(""),
  research_depth: z.number().int().min(1).max(8).default(3),
  homework_policy: z.enum(["REFERENCE_ONLY", "NAVIGATION_ONLY"]).default("REFERENCE_ONLY"),
  ask_on_uncertain: z.boolean().default(true),
  candidates: z.array(AppCandidateSchema).default([]),
});

const MODEL_NAME = LEGACY_DEMO_MODEL;

const APP_HINTS = [
  { name: "微信", pkg: "com.tencent.mm" },
  { name: "抖音", pkg: "com.ss.android.ugc.aweme" },
  { name: "淘宝", pkg: "com.taobao.taobao" },
  { name: "哔哩哔哩", pkg: "tv.danmaku.bili" },
  { name: "小红书", pkg: "com.xingin.xhs" },
  { name: "YouTube", pkg: "com.google.android.youtube" },
  { name: "Chrome", pkg: "com.android.chrome" },
];

function inferTaskMode(text: string): "GENERAL" | "SEARCH" | "RESEARCH" | "HOMEWORK" {
  const normalized = text.toLowerCase();
  if (
    normalized.includes("homework") ||
    normalized.includes("exercise") ||
    normalized.includes("assignment") ||
    normalized.includes("作业")
  ) {
    return "HOMEWORK";
  }
  if (
    normalized.includes("research") ||
    normalized.includes("summarize") ||
    normalized.includes("article") ||
    normalized.includes("总结")
  ) {
    return "RESEARCH";
  }
  if (
    normalized.includes("search") ||
    normalized.includes("find") ||
    normalized.includes("lookup") ||
    normalized.includes("搜索")
  ) {
    return "SEARCH";
  }
  return "GENERAL";
}

function inferSearchQuery(text: string): string {
  if (!text.trim()) return "";
  const patterns = [
    /(?:search|find|lookup|搜索|查找)\s*[:：]?\s*([^\s,，。!?！？]{1,60})/iu,
    /(?:about|关于)\s*[:：]?\s*([^\s,，。!?！？]{1,60})/iu,
  ];
  for (const pattern of patterns) {
    const matched = text.match(pattern)?.[1]?.trim();
    if (matched) return matched;
  }
  return "";
}

function inferTargetAppFromText(text: string): { name: string; pkg: string } | null {
  for (const app of APP_HINTS) {
    if (text.toLowerCase().includes(app.name.toLowerCase())) return app;
  }
  return null;
}

function buildFallback(messages: Array<z.infer<typeof ChatMessageSchema>>) {
  const latestUserMessage = [...messages].reverse().find((msg) => msg.role === "user")?.content ?? "";
  const targetApp = inferTargetAppFromText(latestUserMessage);
  const taskMode = inferTaskMode(latestUserMessage);
  const searchQuery = inferSearchQuery(latestUserMessage);
  const inferredGoal = latestUserMessage.trim() || "请描述你要完成的任务";
  const ready = latestUserMessage.length >= 6 && !!targetApp;

  const reply = ready
    ? `目标已确认：${inferredGoal}。准备好后可以开始。`
    : "请告诉我你要使用哪个应用，以及希望完成什么操作。";

  const candidates = targetApp
    ? []
    : APP_HINTS.slice(0, 3).map((app) => ({
        app_name: app.name,
        package_name: app.pkg,
        reason: "常见可选应用",
      }));

  return {
    reply,
    inferred_goal: inferredGoal,
    target_app_name: targetApp?.name ?? "",
    ready_to_start: ready,
    task_mode: taskMode,
    search_query: searchQuery,
    research_depth: taskMode === "RESEARCH" ? 3 : 1,
    homework_policy: taskMode === "HOMEWORK" ? "REFERENCE_ONLY" : "NAVIGATION_ONLY",
    ask_on_uncertain: true,
    candidates,
  };
}

export async function POST(req: Request) {
  // ========== P0 authentication guard ==========
  const authResult = await authenticateRequest(req);
  if (!authResult.valid) {
    return NextResponse.json(
      {
        success: false,
        error: "authentication_failed",
        details: authResult.error,
      },
      { status: 401 },
    );
  }

  // Per-device rate limit: cap expensive model calls per authenticated identity.
  const limited = await rateLimitGuard(authResult.client_id);
  if (limited) return limited;

  try {
    const rawBody = await req.json();
    const parsedBody = ChatRequestSchema.safeParse(rawBody);
    if (!parsedBody.success) {
      return NextResponse.json({ success: false, error: "invalid_chat_goal_request" }, { status: 400 });
    }

    const { messages, attachments } = parsedBody.data;
    const conversation = messages
      .slice(-16)
      .map((msg) => `${msg.role === "user" ? "user" : "assistant"}: ${msg.content}`)
      .join("\n");

    // Images become vision Parts; text-like files are inlined into the prompt;
    // other binaries are noted by name so the model knows they exist.
    const imageAttachments = attachments.filter((a) => a.kind === "image" && a.data_base64);
    const fileNotes = attachments
      .filter((a) => a.kind === "file")
      .map((a) =>
        a.text_content
          ? `\n\n[用户上传文件 "${a.name}" (${a.mime_type})]:\n${a.text_content}`
          : `\n\n[用户上传文件 "${a.name}" (${a.mime_type})，为二进制文件，内容未提取，仅文件名可见]`,
      )
      .join("");

    // Initialize the client per-request: a broken or partial GenAI config
    // should degrade to the deterministic fallback instead of a 500.
    let ai: ReturnType<typeof getGenAIClient> | null = null;
    if (GENAI_CLIENT_ENABLED) {
      try {
        ai = getGenAIClient();
      } catch (error) {
        console.warn(
          `[chat-goal] genai client unavailable, using fallback: ${String((error as Error)?.message || error)}`,
        );
      }
    }

    if (!ai) {
      return NextResponse.json({ success: true, ...buildFallback(messages) });
    }

    const systemPrompt = `
You are a goal-clarification assistant for mobile automation.
Return strict JSON only:
{
  "reply": "assistant response",
  "inferred_goal": "normalized goal",
  "target_app_name": "single app name or empty if unclear",
  "ready_to_start": true/false,
  "task_mode": "GENERAL|SEARCH|RESEARCH|HOMEWORK",
  "search_query": "query text or empty",
  "research_depth": 1-8,
  "homework_policy": "REFERENCE_ONLY|NAVIGATION_ONLY",
  "ask_on_uncertain": true/false,
  "candidates": [{"app_name":"app","package_name":"pkg","reason":"why"}]
}

Rules:
1) If app or task is unclear, ask one concise question and set ready_to_start=false.
2) If uncertain about app, output 2-3 candidates.
3) Keep search/research/homework mode accurate from user intent.
4) Keep response concise and safe.
    `.trim();

    // Build a single prompt string, then attach images as inline vision Parts
    // when present. An array `contents` is consumed natively by Gemini and
    // converted to OpenAI `image_url` parts by the compat adapter, so this is
    // provider-agnostic.
    const promptText = `${systemPrompt}\n\nConversation:\n${conversation}${fileNotes}`;
    const contents =
      imageAttachments.length > 0
        ? [
            { text: promptText },
            ...imageAttachments.map((a) => ({
              inlineData: { mimeType: a.mime_type, data: a.data_base64 as string },
            })),
          ]
        : promptText;

    const response = await ai.models.generateContent({
      model: MODEL_NAME,
      contents,
      config: {
        responseMimeType: "application/json",
        temperature: 0.2,
      },
    });

    const responseText = response.text || "{}";
    // The model can return non-JSON despite responseMimeType=application/json;
    // a raw JSON.parse throw must degrade to the deterministic fallback, not a 500.
    let parsedJson: unknown;
    try {
      parsedJson = JSON.parse(responseText);
    } catch {
      return NextResponse.json({ success: true, ...buildFallback(messages) });
    }
    const parsedResponse = ChatGoalResponseSchema.safeParse(parsedJson);
    if (!parsedResponse.success) {
      return NextResponse.json({ success: true, ...buildFallback(messages) });
    }

    const normalized = parsedResponse.data;
    return NextResponse.json({
      success: true,
      ...normalized,
      candidates: normalized.candidates.slice(0, 3),
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "unknown error";
    return NextResponse.json(
      {
        success: false,
        error: "chat_goal_failed",
        details: process.env.NODE_ENV === "development" ? message : undefined,
      },
      { status: 500 },
    );
  }
}
