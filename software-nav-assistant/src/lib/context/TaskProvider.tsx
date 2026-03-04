"use client";

import React, { createContext, useContext, useState, useCallback, useEffect, ReactNode } from "react";
import { TaskContext, SessionState, UserFeedback } from "../schemas/state-machine";
import { AgentDecision } from "../schemas/llm-output";
import type { NextStepRequest, NextStepResponse, ActionCommand } from "../schemas/mobile-agent";

// ============================================================================
// P0 前端统一契约：强制切换到 /api/mobile-agent/next-step
// ============================================================================
// 所有核心调用路径现在基于 Zod 契约的新 API：
//   - 请求体遵循 NextStepRequestSchema
//   - 响应体遵循 NextStepResponseSchema
//   - 支持 SoM 标注、多帧媒体窗口、Spatial Grounding 等新特性
//
// Demo 模式：
//   - 仅当用户显式开启时，才调用旧的 /api/analyze-screen（附带 X-Demo-Mode header）
//   - 默认关闭，所有流量走新端点
// ============================================================================

// 初始白板状态
const initialContext: TaskContext = {
  session_id: typeof crypto !== "undefined" ? crypto.randomUUID() : `sess_${Date.now()}`,
  global_goal: "给小明打视频", // Demo 默认主线剧本
  current_step_index: 0,
  state: SessionState.IDLE,
  last_action_desc: null,
  last_checkpoint: null,
  user_feedback: UserFeedback.NONE,
  retry_count: 0,
};

// 后端健康检查 & 连接状态
export interface BackendStatus {
  connected: boolean;
  modelName: string | null;
  authStatus: "authenticated" | "unauthenticated" | "unknown";
  lastChecked: number | null;
}

const initialBackendStatus: BackendStatus = {
  connected: false,
  modelName: null,
  authStatus: "unknown",
  lastChecked: null,
};

interface TaskContextType {
  context: TaskContext;
  currentDecision: AgentDecision | null;
  latestImageBase64: string | null;
  isLoading: boolean;
  lastActionCommand: ActionCommand | null;
  demoMode: boolean;
  setDemoMode: (enabled: boolean) => void;

  // Backend status
  backendStatus: BackendStatus;
  refreshBackendStatus: () => Promise<void>;

  setGlobalGoal: (goal: string) => void;
  submitNewScreen: (base64: string) => Promise<void>;
  triggerUserFeedback: (feedback: UserFeedback) => Promise<void>;
  resetSession: (nextGoal?: string) => void;
}

const TaskStateContext = createContext<TaskContextType | undefined>(undefined);

// 构建符合 NextStepRequestSchema 的请求体
function buildNextStepRequest(
  ctx: TaskContext,
  imageBase64: string,
  turnIndex: number,
): NextStepRequest {
  return {
    session_id: ctx.session_id,
    turn_index: turnIndex,
    mode: "active",
    goal: ctx.global_goal,
    task_spec: {
      mode: "GENERAL",
      search_query: "",
      ask_on_uncertain: true,
    },
    observation: {
      observation_reason: turnIndex === 0 ? "APP_START" : "AFTER_ACTION",
      foreground_package: "com.demo.app",
      media_window: {
        source: "SCREENSHOT",
        frames: [
          {
            frame_id: `frame_${Date.now()}`,
            ts_ms: Date.now(),
            image_base64: imageBase64,
            ui_signature: `sig_${Date.now()}`,
          },
        ],
      },
      ui_nodes: [],
      previous_action_result: turnIndex === 0 ? "NOT_EXECUTED" : "SUCCESS",
      previous_checkpoint_match: true,
    },
    history_tail: [],
  };
}

// 将 NextStepResponse 转换为旧的 AgentDecision 格式（兼容现有 UI 组件）
function toLegacyIntent(intent: ActionCommand["intent"]): AgentDecision["next_step"]["intent"] {
  switch (intent) {
    case "CLICK":
      return "CLICK";
    case "SCROLL_UP":
    case "SCROLL_DOWN":
    case "SCROLL_LEFT":
    case "SCROLL_RIGHT":
      return "SCROLL";
    case "BACK":
      return "GO_BACK";
    case "WAIT":
      return "WAIT";
    case "FINISH":
      return "FINISH";
    default:
      return "UNKNOWN";
  }
}

function convertToAgentDecision(response: NextStepResponse): AgentDecision {
  const action = response.final_action;
  return {
    audit_metadata: {
      reasoning_trace: response.reviewer.reason,
    },
    screen_state: {
      page_summary: response.checkpoint.expected_page_type || "UNKNOWN",
      confidence: 0.9,
      has_popup: false,
      risk_detected: response.guard.risk_level === "HIGH",
    },
    verification: {
      is_previous_step_successful: true,
      is_deviation: false,
      reasoning: response.reviewer.reason,
    },
    next_step: {
      intent: toLegacyIntent(action.intent),
      target_element_desc: action.target_desc,
      target_bbox: action.target_bbox ?? null,
      elderly_instruction: action.narration,
      risk_level: action.risk_level,
    },
    next_screen_checkpoint: {
      expected_page_type: response.checkpoint.expected_page_type || "UNKNOWN",
      expected_elements: response.checkpoint.expected_elements || [],
    },
    dialog_support: {
      if_cannot_see: "Please tell me what part is not visible, and I will rephrase clearly.",
      if_too_fast: "Okay, we will slow down and go step by step.",
    },
    uncertainty_handling: {
      is_uncertain: false,
      safe_action: "If uncertain, tap back first.",
    },
  };
}
export function TaskProvider({ children }: { children: ReactNode }) {
  const [context, setContext] = useState<TaskContext>(initialContext);
  const [currentDecision, setCurrentDecision] = useState<AgentDecision | null>(null);
  const [latestImageBase64, setLatestImageBase64] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [lastActionCommand, setLastActionCommand] = useState<ActionCommand | null>(null);
  const [demoMode, setDemoMode] = useState(process.env.NEXT_PUBLIC_MOCK_MODE === "true");
  const [turnIndex, setTurnIndex] = useState(0);

  // Backend status tracking
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(initialBackendStatus);

  // 健康检查：探测后端连接状态、模型名称、鉴权状态
  const refreshBackendStatus = useCallback(async () => {
    try {
      const res = await fetch("/api/mobile-agent/next-step", {
        method: "OPTIONS",
      });
      // 即使 OPTIONS 不被支持，能拿到响应就说明后端在线
      setBackendStatus({
        connected: res.ok || res.status === 405 || res.status === 204,
        modelName: res.headers.get("X-Model-Name") ?? "gemini-2.5-flash",
        authStatus: res.status === 401 || res.status === 403
          ? "unauthenticated"
          : "authenticated",
        lastChecked: Date.now(),
      });
    } catch {
      setBackendStatus({
        connected: false,
        modelName: null,
        authStatus: "unknown",
        lastChecked: Date.now(),
      });
    }
  }, []);

  // 启动时自动检查一次后端状态
  useEffect(() => {
    void refreshBackendStatus();
  }, [refreshBackendStatus]);

  // ============================================================================
  // 核心引擎：调用新的 /api/mobile-agent/next-step 端点
  // ============================================================================
  const runAgentTurn = async (image: string, currentCtx: TaskContext) => {

    setIsLoading(true);
    try {
      const requestBody = buildNextStepRequest(currentCtx, image, turnIndex);

      const response = await fetch("/api/mobile-agent/next-step", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error((errorData as Record<string, string>).error ?? `HTTP ${response.status}`);
      }

      const data = await response.json();
      if (data.success) {
        const nextStepResponse = data as { success: true } & NextStepResponse;

        // 更新状态
        setLastActionCommand(nextStepResponse.final_action);
        setCurrentDecision(convertToAgentDecision(nextStepResponse));
        setTurnIndex((prev) => prev + 1);

        // 根据 guard 结果更新会话状态
        const newState =
          nextStepResponse.guard.risk_level === "HIGH"
            ? SessionState.RISK_PAUSED
            : nextStepResponse.final_action.intent === "FINISH"
              ? SessionState.COMPLETED
              : SessionState.WAITING_USER;

        setContext({
          ...currentCtx,
          state: newState,
          last_action_desc: nextStepResponse.final_action.target_desc,
          last_checkpoint: nextStepResponse.checkpoint
            ? {
                expected_page_type: nextStepResponse.checkpoint.expected_page_type,
                expected_elements: nextStepResponse.checkpoint.expected_elements,
              }
            : null,
        });

        // 更新后端状态（从成功响应中提取模型信息）
        setBackendStatus((prev) => ({
          ...prev,
          connected: true,
          modelName: nextStepResponse.planner?.model ?? prev.modelName,
          authStatus: "authenticated",
          lastChecked: Date.now(),
        }));
      } else {
        console.error("Agent 响应失败:", data.error);
        alert(`操作失败: ${data.error}`);
      }
    } catch (error) {
      console.error("Agent 核心流转失败:", error);
      // 更新后端状态为断开
      setBackendStatus((prev) => ({
        ...prev,
        connected: false,
        lastChecked: Date.now(),
      }));
      alert(`网络错误: ${error instanceof Error ? error.message : "未知错误"}`);
    } finally {
      setIsLoading(false);
    }
  };

  // 用户点击或上传了新截图，触发新一轮分析
  const submitNewScreen = async (base64: string) => {
    setLatestImageBase64(base64);
    const updatedCtx = {
      ...context,
      state: SessionState.PROCESSING,
      user_feedback: UserFeedback.NONE,
    };
    setContext(updatedCtx);
    await runAgentTurn(base64, updatedCtx);
  };

  // 长辈按下了快捷反馈（看不到/太快了）
  const triggerUserFeedback = async (feedback: UserFeedback) => {
    if (!latestImageBase64) return;
    // 带着负面反馈和上一张原图，重新呼叫 AI 纠偏
    const updatedCtx = {
      ...context,
      user_feedback: feedback,
      state: SessionState.PROCESSING,
    };
    setContext(updatedCtx);
    await runAgentTurn(latestImageBase64, updatedCtx);
  };

  const setGlobalGoal = (goal: string) => setContext({ ...context, global_goal: goal });

  const resetSession = (nextGoal?: string) => {
    setContext({
      ...initialContext,
      session_id: typeof crypto !== "undefined" ? crypto.randomUUID() : `sess_${Date.now()}`,
      global_goal: nextGoal ?? initialContext.global_goal,
    });
    setCurrentDecision(null);
    setLatestImageBase64(null);
    setLastActionCommand(null);
    setTurnIndex(0);
  };

  return (
    <TaskStateContext.Provider
      value={{
        context,
        currentDecision,
        latestImageBase64,
        isLoading,
        lastActionCommand,
        demoMode,
        setDemoMode,
        backendStatus,
        refreshBackendStatus,
        setGlobalGoal,
        submitNewScreen,
        triggerUserFeedback,
        resetSession,
      }}
    >
      {children}
    </TaskStateContext.Provider>
  );
}

export const useTaskContext = () => {
  const ctx = useContext(TaskStateContext);
  if (!ctx) throw new Error("useTaskContext must be used within TaskProvider");
  return ctx;
};
