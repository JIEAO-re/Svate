import { NextResponse } from "next/server";
import { z } from "zod";
import { getGenAIClient } from "@/lib/mobile-agent/genai-client";
import { LEGACY_DEMO_MODEL, GENAI_CLIENT_ENABLED } from "@/lib/mobile-agent/env";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";

const ChatMessageSchema = z.object({
  role: z.enum(["user", "assistant"]),
  content: z.string().min(1).max(1000),
});

const ChatRequestSchema = z.object({
  messages: z.array(ChatMessageSchema).min(1).max(40),
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

const ai = GENAI_CLIENT_ENABLED ? getGenAIClient() : null;
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

  try {
    const rawBody = await req.json();
    const parsedBody = ChatRequestSchema.safeParse(rawBody);
    if (!parsedBody.success) {
      return NextResponse.json({ success: false, error: "invalid_chat_goal_request" }, { status: 400 });
    }

    const { messages } = parsedBody.data;
    const conversation = messages
      .slice(-16)
      .map((msg) => `${msg.role === "user" ? "user" : "assistant"}: ${msg.content}`)
      .join("\n");

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

    const response = await ai.models.generateContent({
      model: MODEL_NAME,
      contents: `${systemPrompt}\n\nConversation:\n${conversation}`,
      config: {
        responseMimeType: "application/json",
        temperature: 0.2,
      },
    });

    const responseText = response.text || "{}";
    const parsedResponse = ChatGoalResponseSchema.safeParse(JSON.parse(responseText));
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
