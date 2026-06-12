"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";

export function StatusIndicator() {
  const { backendStatus, refreshBackendStatus } = useTaskContext();

  return (
    <div className="flex items-center gap-3 flex-wrap text-xs font-mono">
      {/* Backend connectivity */}
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

      {/* Model name: only shown when the backend actually reported one */}
      {backendStatus.modelName ? (
        <div className="px-2.5 py-1 rounded-full bg-indigo-900/50 border border-indigo-700/50 text-indigo-300">
          {backendStatus.modelName}
        </div>
      ) : (
        <div className="px-2.5 py-1 rounded-full bg-slate-800 border border-slate-700 text-slate-400">
          Model Unknown
        </div>
      )}

      {/* Authentication state */}
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
    </div>
  );
}

/**
 * Warn when the backend is unreachable or rejected our credentials, since live
 * navigation cannot work in either case.
 */
export function BackendStatusBanner() {
  const { backendStatus } = useTaskContext();

  const unauthenticated = backendStatus.authStatus === "unauthenticated";
  if (backendStatus.connected && !unauthenticated) return null;

  return (
    <div className="mx-auto max-w-2xl mt-4 px-4 py-3 rounded-xl bg-amber-900/20 border border-amber-700/40 text-amber-200 text-sm flex items-center gap-3">
      <span className="text-lg">&#x26A0;</span>
      <div className="flex-1">
        {unauthenticated ? (
          <>
            <span className="font-bold">后端鉴权失败。</span>
            {" "}服务器拒绝了当前请求（401/403），请检查访问凭证配置后重试。
          </>
        ) : (
          <>
            <span className="font-bold">后端未连接。</span>
            {" "}当前无法使用实时导航功能，请检查后端服务是否正常运行。
          </>
        )}
      </div>
    </div>
  );
}
