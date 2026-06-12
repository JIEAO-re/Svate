"use client";

import React, { createContext, useContext, useState, useCallback, useEffect, useRef, ReactNode } from "react";
import { TaskContext, SessionState, UserFeedback } from "../schemas/state-machine";
import type { NextStepRequest, NextStepResponse } from "../schemas/mobile-agent";

// ============================================================================
// P0 frontend contract unification: force traffic onto /api/mobile-agent/next-step
// ============================================================================
// All core request paths use the new API backed by the Zod contract:
//   - Request payloads follow NextStepRequestSchema
//   - Responses follow NextStepResponseSchema
//   - Supports SoM markers, multi-frame media windows, Spatial Grounding, and other new features
// ============================================================================

// Synthetic source package for frames captured by this web demo through
// browser screen sharing. A real Android client reports the actual foreground
// app package here; the web demo has no such concept, so it self-describes
// instead of impersonating an installed app.
const WEB_DEMO_SOURCE_PACKAGE = "web.demo.capture";

// Keep only the most recent N actions in the history_tail we send upstream.
const HISTORY_TAIL_LIMIT = 5;

// One entry of the request's history_tail, as defined by the contract.
type HistoryItem = NextStepRequest["history_tail"][number];

// Placeholder result for the most recent action until the next observation
// lets us infer its real outcome (it is backfilled before being sent).
const HISTORY_RESULT_PENDING = "PENDING";

// Initial blank-slate state
const initialContext: TaskContext = {
  session_id: typeof crypto !== "undefined" ? crypto.randomUUID() : `sess_${Date.now()}`,
  global_goal: "给小明打视频", // Default demo storyline goal
  current_step_index: 0,
  state: SessionState.IDLE,
  last_action_desc: null,
  last_checkpoint: null,
  user_feedback: UserFeedback.NONE,
  retry_count: 0,
};

// Backend health check and connection state
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
  // The latest backend response, exposed verbatim so UI components can only
  // render fields the server actually produced.
  currentTurn: NextStepResponse | null;
  latestImageBase64: string | null;
  isLoading: boolean;

  // Backend status
  backendStatus: BackendStatus;
  refreshBackendStatus: () => Promise<void>;

  setGlobalGoal: (goal: string) => void;
  submitNewScreen: (base64: string) => Promise<void>;
  triggerUserFeedback: (feedback: UserFeedback) => Promise<void>;
  resetSession: (nextGoal?: string) => void;
}

const TaskStateContext = createContext<TaskContextType | undefined>(undefined);

// Stable djb2 hash over the captured frame's data URL: identical frame content
// always yields the same signature. Used both as the contract's ui_signature
// and as a client-side "did the screen change" signal between turns.
function computeUiSignature(dataUrl: string): string {
  let hash = 5381;
  for (let i = 0; i < dataUrl.length; i++) {
    hash = ((hash << 5) + hash + dataUrl.charCodeAt(i)) >>> 0;
  }
  return `sig_${hash.toString(16).padStart(8, "0")}`;
}

// What the contract lets us report about the previous turn.
interface PreviousOutcome {
  result: NextStepRequest["observation"]["previous_action_result"];
  checkpointMatch: boolean;
}

// Derive the most honest previous-turn outcome the contract can express.
// The web demo never executes actions itself -- the elderly user does -- so the
// only client-side signals are the quick feedback buttons and whether the frame
// content actually changed. The server-side reviewer remains the authoritative
// verifier of checkpoints.
function derivePreviousOutcome(
  ctx: TaskContext,
  turnIndex: number,
  screenChanged: boolean,
): PreviousOutcome {
  if (turnIndex === 0) {
    // First turn: no action was issued and no checkpoint exists yet, so the
    // match flag is vacuously true.
    return { result: "NOT_EXECUTED", checkpointMatch: true };
  }
  switch (ctx.user_feedback) {
    case UserFeedback.CANT_SEE:
    case UserFeedback.TOO_FAST:
      // The user could not find the target or asked for a repeat: the action
      // was not performed, so the expected page cannot have been reached.
      return { result: "NOT_EXECUTED", checkpointMatch: false };
    case UserFeedback.WRONG_PAGE:
      // The user reports landing on the wrong page: the action was attempted
      // but missed the checkpoint.
      return { result: "FAILED", checkpointMatch: false };
    default:
      // No negative feedback. An unchanged frame proves nothing happened yet;
      // a changed frame is the best available client-side evidence that the
      // user executed the instruction.
      return screenChanged
        ? { result: "SUCCESS", checkpointMatch: true }
        : { result: "NOT_EXECUTED", checkpointMatch: false };
  }
}

// Build a request body that conforms to NextStepRequestSchema
function buildNextStepRequest(
  ctx: TaskContext,
  imageBase64: string,
  turnIndex: number,
  uiSignature: string,
  previousOutcome: PreviousOutcome,
  historyTail: HistoryItem[],
): NextStepRequest {
  const now = Date.now();
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
      foreground_package: WEB_DEMO_SOURCE_PACKAGE,
      media_window: {
        source: "SCREENSHOT",
        frames: [
          {
            frame_id: `frame_${turnIndex}_${now}`,
            ts_ms: now,
            image_base64: imageBase64,
            ui_signature: uiSignature,
          },
        ],
      },
      ui_nodes: [],
      previous_action_result: previousOutcome.result,
      previous_checkpoint_match: previousOutcome.checkpointMatch,
    },
    history_tail: historyTail,
  };
}

export function TaskProvider({ children }: { children: ReactNode }) {
  const [context, setContext] = useState<TaskContext>(initialContext);
  const [currentTurn, setCurrentTurn] = useState<NextStepResponse | null>(null);
  const [latestImageBase64, setLatestImageBase64] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [turnIndex, setTurnIndex] = useState(0);

  // Rolling log of the most recent final actions, sent as history_tail.
  const historyTailRef = useRef<HistoryItem[]>([]);
  // Signature of the frame the last completed turn was based on, used to
  // detect whether the screen actually changed since then.
  const lastUiSignatureRef = useRef<string | null>(null);

  // Backend status tracking
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(initialBackendStatus);

  // Health check: probe backend reachability and authentication state.
  // Status mapping:
  //   2xx/405 -> reachable (405 still proves the route exists)
  //   401/403 -> credentials rejected, surface as unauthenticated
  //   404/5xx -> error state, treated as not connected
  // The model name is only taken from an explicit X-Model-Name response header
  // or a later successful turn; it is never assumed.
  const refreshBackendStatus = useCallback(async () => {
    try {
      const res = await fetch("/api/mobile-agent/next-step", {
        method: "OPTIONS",
      });
      const authFailed = res.status === 401 || res.status === 403;
      const reachable = res.ok || res.status === 405;
      setBackendStatus((prev) => ({
        connected: reachable,
        // Keep a model name learned from a real response; never fabricate one.
        modelName: res.headers.get("X-Model-Name") ?? prev.modelName,
        // OPTIONS does not exercise auth, so a passing probe alone cannot
        // prove we are authenticated.
        authStatus: authFailed
          ? "unauthenticated"
          : reachable && prev.authStatus === "authenticated"
            ? "authenticated"
            : "unknown",
        lastChecked: Date.now(),
      }));
    } catch {
      setBackendStatus((prev) => ({
        connected: false,
        modelName: prev.modelName,
        authStatus: "unknown",
        lastChecked: Date.now(),
      }));
    }
  }, []);

  // Run a backend status check once on startup
  useEffect(() => {
    void refreshBackendStatus();
  }, [refreshBackendStatus]);

  // ============================================================================
  // Core engine: call the new /api/mobile-agent/next-step endpoint
  // ============================================================================
  const runAgentTurn = async (image: string, currentCtx: TaskContext) => {

    setIsLoading(true);
    try {
      const uiSignature = computeUiSignature(image);
      const screenChanged =
        lastUiSignatureRef.current !== null && uiSignature !== lastUiSignatureRef.current;
      const previousOutcome = derivePreviousOutcome(currentCtx, turnIndex, screenChanged);

      // Backfill the pending result of the latest history entry now that the
      // outcome of that action can be inferred from feedback / screen change.
      const history = [...historyTailRef.current];
      if (history.length > 0 && history[history.length - 1].result === HISTORY_RESULT_PENDING) {
        history[history.length - 1] = {
          ...history[history.length - 1],
          result: previousOutcome.result,
        };
        historyTailRef.current = history;
      }

      const requestBody = buildNextStepRequest(
        currentCtx,
        image,
        turnIndex,
        uiSignature,
        previousOutcome,
        history,
      );

      const response = await fetch("/api/mobile-agent/next-step", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        // 401/403 means the backend is reachable but rejected our credentials.
        if (response.status === 401 || response.status === 403) {
          setBackendStatus((prev) => ({
            ...prev,
            authStatus: "unauthenticated",
            lastChecked: Date.now(),
          }));
        }
        const errorData = await response.json().catch(() => ({}));
        throw new Error((errorData as Record<string, string>).error ?? `HTTP ${response.status}`);
      }

      const data = await response.json();
      if (data.success) {
        const nextStepResponse = data as { success: true } & NextStepResponse;

        // Remember which frame this instruction was based on, so the next
        // turn can tell whether the screen actually changed.
        lastUiSignatureRef.current = uiSignature;

        // Record the action we are about to show the user; its result is
        // backfilled at the start of the next turn.
        historyTailRef.current = [
          ...historyTailRef.current,
          {
            action_intent: nextStepResponse.final_action.intent,
            target_desc: nextStepResponse.final_action.target_desc,
            result: HISTORY_RESULT_PENDING,
          },
        ].slice(-HISTORY_TAIL_LIMIT);

        // Update UI state
        setCurrentTurn(nextStepResponse);
        setTurnIndex((prev) => prev + 1);

        // Update session state based on the guard result
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

        // Update backend status with model information from the successful response
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
      // Mark the backend as disconnected
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

  // Trigger a new analysis pass after the user clicks or uploads a new screenshot
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

  // Handle quick feedback from the elderly user, such as can't see or too fast
  const triggerUserFeedback = async (feedback: UserFeedback) => {
    if (!latestImageBase64) return;
    // Reinvoke the AI with the negative feedback and the previous screenshot for recovery
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
    setCurrentTurn(null);
    setLatestImageBase64(null);
    setTurnIndex(0);
    historyTailRef.current = [];
    lastUiSignatureRef.current = null;
  };

  return (
    <TaskStateContext.Provider
      value={{
        context,
        currentTurn,
        latestImageBase64,
        isLoading,
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
