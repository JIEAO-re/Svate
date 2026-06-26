"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";
import { SessionState, UserFeedback } from "@/lib/schemas/state-machine";
import { AlertTriangle, EyeOff, Rewind, RefreshCw, CheckCircle } from "lucide-react";

export function GuidanceCard() {
  const { context, currentTurn, isLoading, triggerUserFeedback, resetSession } = useTaskContext();

  // Scenario 1: initial waiting state
  if (context.state === SessionState.IDLE && !isLoading) {
    return (
      <div className="bg-white p-6 rounded-3xl shadow-sm border border-gray-200 mt-6 text-center">
        <h2 className="text-3xl font-bold text-gray-900 mb-2">今天想做什么？</h2>
        <p className="text-xl text-gray-500">目标：{context.global_goal}</p>
      </div>
    );
  }

  // Scenario 2: high-risk hard stop — inverted to dark for maximum contrast.
  if (context.state === SessionState.RISK_PAUSED || currentTurn?.final_action.risk_level === "HIGH") {
    return (
      <div className="bg-gray-900 p-6 rounded-3xl shadow-xl border border-gray-900 mt-6 animate-in slide-in-from-bottom-4">
        <div className="flex items-center gap-3 mb-4">
          <AlertTriangle className="w-12 h-12 text-white animate-pulse" />
          <h2 className="text-3xl font-black text-white">危险！请停止</h2>
        </div>
        <p className="text-2xl text-white leading-snug font-bold mb-6 bg-white/10 p-4 rounded-xl">
          {currentTurn?.final_action.narration ||
           "当前页面涉及资金或重要隐私。为了保护您的安全，向导已暂停。"}
        </p>
        <button
          className="w-full py-4 text-2xl font-black bg-white text-gray-900 rounded-2xl active:bg-gray-100 shadow-md"
          onClick={() => resetSession()}
        >
          退回安全区，重新开始
        </button>
      </div>
    );
  }

  // Scenario 3: success state
  if (context.state === SessionState.COMPLETED) {
    return (
      <div className="bg-gray-50 p-6 rounded-3xl shadow-sm border border-gray-300 mt-6 text-center">
         <CheckCircle className="w-16 h-16 text-gray-900 mx-auto mb-4" />
         <h2 className="text-4xl font-black text-gray-900 mb-4">找到了！</h2>
         <p className="text-2xl text-gray-600 font-bold mb-6">目标就在屏幕上，请您自己点击完成操作。您真棒！</p>
         <button onClick={() => resetSession()} className="w-full py-4 bg-black text-white rounded-2xl text-2xl font-bold hover:bg-gray-800 transition-colors">开始新任务</button>
      </div>
    );
  }

  // Scenario 4: normal guidance output for the main path and recovery flow
  const isRecovering = context.state === SessionState.RECOVERING;

  return (
    <div className={`p-6 rounded-3xl shadow-sm mt-6 flex flex-col gap-6 transition-colors duration-500 border ${isRecovering ? 'bg-gray-50 border-gray-300' : 'bg-white border-gray-200'}`}>

      {/* Step indicator */}
      <div className="flex items-center justify-between text-gray-500 font-bold text-lg">
        {isRecovering ? (
           <span className="bg-gray-200 text-gray-800 px-4 py-1.5 rounded-full">别着急，咱们慢慢找</span>
        ) : (
           <span className="bg-gray-100 text-gray-700 px-4 py-1.5 rounded-full">第 {context.current_step_index} 步</span>
        )}
      </div>

      {/* Main plain-language instruction */}
      <div className={`min-h-[100px] transition-opacity duration-300 ${isLoading ? 'opacity-30' : 'opacity-100'}`}>
        <p className="text-[2rem] leading-snug font-black tracking-wide text-gray-900">
          “{isLoading ? "小助手正在看.." : (currentTurn?.final_action.narration || "等待指令...")}”
        </p>
      </div>

      {/* Quick safety feedback buttons (only while waiting for the user to act) */}
      {(context.state === SessionState.WAITING_USER || context.state === SessionState.RECOVERING) && !isLoading && (
        <div className="pt-6 border-t border-gray-200">
          <p className="text-gray-400 text-lg font-bold mb-4">如果遇到困难，点这里告诉我：</p>
          <div className="grid grid-cols-2 gap-3">
            <button
              onClick={() => triggerUserFeedback(UserFeedback.CANT_SEE)}
              className="flex flex-col items-center justify-center p-4 bg-gray-50 text-gray-700 border border-gray-200 rounded-2xl active:bg-gray-200 transition-colors"
            >
              <EyeOff className="w-8 h-8 mb-2 text-gray-900" />
              <span className="text-xl font-bold">我找不到</span>
            </button>
            <button
              onClick={() => triggerUserFeedback(UserFeedback.TOO_FAST)}
              className="flex flex-col items-center justify-center p-4 bg-gray-50 text-gray-700 border border-gray-200 rounded-2xl active:bg-gray-200 transition-colors"
            >
              <RefreshCw className="w-8 h-8 mb-2 text-gray-900" />
              <span className="text-xl font-bold">重复一遍</span>
            </button>
            <button
              onClick={() => triggerUserFeedback(UserFeedback.WRONG_PAGE)}
              className="col-span-2 flex items-center justify-center gap-3 p-4 bg-gray-100 text-gray-900 border border-gray-300 rounded-2xl active:bg-gray-200 transition-colors mt-2"
            >
              <Rewind className="w-8 h-8" />
              <span className="text-xl font-bold">好像点错了，不是这个页面</span>
            </button>
          </div>
        </div>
      )}

      {/* Fault-tolerance hint: after repeated failures, suggest asking family for help */}
      {context.retry_count >= 2 && (
        <div className="text-center mt-2 px-4 py-3 bg-gray-100 text-gray-700 rounded-xl text-lg font-bold">
          没关系，如果您一直找不到，建议晚点让家人帮您看看。
        </div>
      )}
    </div>
  );
}
