"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";
import { SessionState, UserFeedback } from "@/lib/schemas/state-machine";
import { AlertTriangle, EyeOff, Rewind, RefreshCw, CheckCircle } from "lucide-react";

export function GuidanceCard() {
  const { context, currentDecision, isLoading, triggerUserFeedback, resetSession } = useTaskContext();

  // 场景 1: 初始等待
  if (context.state === SessionState.IDLE && !isLoading) {
    return (
      <div className="bg-white p-6 rounded-3xl shadow-lg border-2 border-gray-100 mt-6 text-center">
        <h2 className="text-3xl font-bold text-gray-800 mb-2">今天想做什么？</h2>
        <p className="text-xl text-gray-500">目标：{context.global_goal}</p>
      </div>
    );
  }

  // 场景 2: 🚨 高危物理熔断 (Demo 拿分点，绝对的视觉冲击)
  if (context.state === SessionState.RISK_PAUSED || currentDecision?.next_step?.risk_level === "HIGH") {
    return (
      <div className="bg-red-50 p-6 rounded-3xl shadow-2xl border-4 border-red-500 mt-6 animate-in slide-in-from-bottom-4">
        <div className="flex items-center gap-3 mb-4">
          <AlertTriangle className="w-12 h-12 text-red-600 animate-pulse" />
          <h2 className="text-3xl font-black text-red-700">危险！请停止</h2>
        </div>
        <p className="text-2xl text-red-900 leading-snug font-bold mb-6 bg-red-100 p-4 rounded-xl">
          {currentDecision?.next_step?.elderly_instruction ||
           "当前页面涉及资金或重要隐私。为了保护您的安全，向导已暂停。"}
        </p>
        <button
          className="w-full py-4 text-2xl font-black bg-white text-red-600 border-2 border-red-200 rounded-2xl active:bg-red-100 shadow-md"
          onClick={() => resetSession()}
        >
          退回安全区，重新开始
        </button>
      </div>
    );
  }

  // 场景 3: 成功完成
  if (context.state === SessionState.COMPLETED) {
    return (
      <div className="bg-green-50 p-6 rounded-3xl shadow-lg border-4 border-green-500 mt-6 text-center">
         <CheckCircle className="w-16 h-16 text-green-600 mx-auto mb-4" />
         <h2 className="text-4xl font-black text-green-800 mb-4">找到了！</h2>
         <p className="text-2xl text-green-700 font-bold mb-6">目标就在屏幕上，请您自己点击完成操作。您真棒！</p>
         <button onClick={() => resetSession()} className="w-full py-4 bg-green-600 text-white rounded-2xl text-2xl font-bold">开始新任务</button>
      </div>
    );
  }

  // 场景 4: 正常指引输出 (主链路与降级恢复)
  const isRecovering = context.state === SessionState.RECOVERING;

  return (
    <div className={`p-6 rounded-3xl shadow-xl mt-6 flex flex-col gap-6 transition-colors duration-500 border-4 ${isRecovering ? 'bg-amber-50 border-amber-300' : 'bg-white border-blue-100'}`}>

      {/* 步骤提示器 */}
      <div className="flex items-center justify-between text-slate-500 font-bold text-lg">
        {isRecovering ? (
           <span className="bg-amber-200 text-amber-900 px-4 py-1.5 rounded-full">💡 别着急，咱们慢慢找</span>
        ) : (
           <span className="bg-blue-100 text-blue-800 px-4 py-1.5 rounded-full">第 {context.current_step_index} 步</span>
        )}
      </div>

      {/* 🌟 右脑区域：大白话主指令 */}
      <div className={`min-h-[100px] transition-opacity duration-300 ${isLoading ? 'opacity-30' : 'opacity-100'}`}>
        <p className={`text-[2rem] leading-snug font-black tracking-wide ${isRecovering ? 'text-amber-900' : 'text-slate-900'}`}>
          “{isLoading ? "小助手正在看..." : (currentDecision?.next_step?.elderly_instruction || "等待指令...")}”
        </p>
      </div>

      {/* 🛡️ 左脑区域：长辈防身反馈按键 (仅在明确等待点击时出现) */}
      {(context.state === SessionState.WAITING_USER || context.state === SessionState.RECOVERING) && !isLoading && (
        <div className="pt-6 border-t-2 border-slate-100">
          <p className="text-slate-400 text-lg font-bold mb-4">如果遇到困难，点这里告诉我：</p>
          <div className="grid grid-cols-2 gap-3">
            <button
              onClick={() => triggerUserFeedback(UserFeedback.CANT_SEE)}
              className="flex flex-col items-center justify-center p-4 bg-slate-50 text-slate-700 border-2 border-slate-200 rounded-2xl active:bg-slate-200 transition-colors shadow-sm"
            >
              <EyeOff className="w-8 h-8 mb-2 text-blue-600" />
              <span className="text-xl font-bold">我找不到</span>
            </button>
            <button
              onClick={() => triggerUserFeedback(UserFeedback.TOO_FAST)}
              className="flex flex-col items-center justify-center p-4 bg-slate-50 text-slate-700 border-2 border-slate-200 rounded-2xl active:bg-slate-200 transition-colors shadow-sm"
            >
              <RefreshCw className="w-8 h-8 mb-2 text-blue-600" />
              <span className="text-xl font-bold">重复一遍</span>
            </button>
            <button
              onClick={() => triggerUserFeedback(UserFeedback.WRONG_PAGE)}
              className="col-span-2 flex items-center justify-center gap-3 p-4 bg-rose-50 text-rose-700 border-2 border-rose-200 rounded-2xl active:bg-rose-100 transition-colors shadow-sm mt-2"
            >
              <Rewind className="w-8 h-8" />
              <span className="text-xl font-bold">好像点错了，不是这个页面</span>
            </button>
          </div>
        </div>
      )}

      {/* 容错纠偏机制：连续找不到，系统提示求助家人 */}
      {context.retry_count >= 2 && (
        <div className="text-center mt-2 px-4 py-3 bg-orange-100 text-orange-800 rounded-xl text-lg font-bold">
          💡 没关系，如果您一直找不到，建议晚点让家人帮您看看。
        </div>
      )}
    </div>
  );
}
