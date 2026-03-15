"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";

export function StatusIndicator() {
  const { backendStatus, demoMode, setDemoMode, refreshBackendStatus } = useTaskContext();

  return (
    <div className="flex items-center gap-3 flex-wrap text-xs font-mono">
      {/* 后端连接状态 */}
      <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-800 border border-slate-700">
        <span
          className={`w-2 h-2 rounded-full ${
            backendStatus.connected
              ? "bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.5)]"
              : "bg-red-400 shadow-[0_0_6px_rgba(248,113,113,0.5)]"
          }`}
        />
        <span className="text-slate-300">
          {backendStatus.connected ? "Backend Online" : "Backend Offline"}
        </span>
        <button
          onClick={() => void refreshBackendStatus()}
          className="ml-1 text-slate-500 hover:text-slate-300 transition-colors"
          title="刷新后端状态"
        >
          &#x21bb;
        </button>
      </div>

      {/* 模型名称 */}
      {backendStatus.modelName && (
        <div className="px-2.5 py-1 rounded-full bg-indigo-900/50 border border-indigo-700/50 text-indigo-300">
          {backendStatus.modelName}
        </div>
      )}

      {/* 鉴权状态 */}
      <div
        className={`px-2.5 py-1 rounded-full border ${
          backendStatus.authStatus === "authenticated"
            ? "bg-emerald-900/30 border-emerald-700/50 text-emerald-300"
            : backendStatus.authStatus === "unauthenticated"
              ? "bg-red-900/30 border-red-700/50 text-red-300"
              : "bg-slate-800 border-slate-700 text-slate-400"
        }`}
      >
        {backendStatus.authStatus === "authenticated"
          ? "Auth OK"
          : backendStatus.authStatus === "unauthenticated"
            ? "Auth Failed"
            : "Auth Unknown"}
      </div>

      {/* Demo 模式开关 */}
      <button
        onClick={() => setDemoMode(!demoMode)}
        className={`px-2.5 py-1 rounded-full border font-bold transition-all ${
          demoMode
            ? "bg-amber-500/20 border-amber-500/50 text-amber-300 hover:bg-amber-500/30"
            : "bg-slate-800 border-slate-700 text-slate-500 hover:text-slate-300 hover:border-slate-600"
        }`}
      >
        {demoMode ? "Demo Mode ON" : "Demo Mode OFF"}
      </button>
    </div>
  );
}

/**
 * Show an actionable hint when demo mode is off and the user tries to use the legacy flow.
 */
export function DemoModeWarningBanner() {
  const { demoMode, setDemoMode, backendStatus } = useTaskContext();

  // Show only when demo mode is off and the backend is offline
  if (demoMode || backendStatus.connected) return null;

  return (
    <div className="mx-auto max-w-2xl mt-4 px-4 py-3 rounded-xl bg-amber-900/20 border border-amber-700/40 text-amber-200 text-sm flex items-center gap-3">
      <span className="text-lg">&#x26A0;</span>
      <div className="flex-1">
        <span className="font-bold">后端未连接。</span>
        {" "}当前无法使用实时导航功能。您可以开启 Demo 模式体验离线演示，或检查后端服务是否正常运行。
      </div>
      <button
        onClick={() => setDemoMode(true)}
        className="shrink-0 px-3 py-1.5 rounded-lg bg-amber-600 text-white font-bold text-xs hover:bg-amber-500 transition-colors"
      >
        开启 Demo
      </button>
    </div>
  );
}
