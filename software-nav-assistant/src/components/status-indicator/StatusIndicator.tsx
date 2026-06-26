"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";

export function StatusIndicator() {
  const { backendStatus, refreshBackendStatus } = useTaskContext();

  return (
    <div className="flex items-center gap-2 flex-wrap text-xs font-mono">
      {/* Backend connectivity */}
      <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-gray-100 border border-gray-200">
        <span
          className={`w-2 h-2 rounded-full ${
            backendStatus.connected ? "bg-gray-900" : "bg-gray-300"
          }`}
        />
        <span className="text-gray-600">
          {backendStatus.connected ? "Backend Online" : "Backend Offline"}
        </span>
        <button
          onClick={() => void refreshBackendStatus()}
          className="ml-1 text-gray-400 hover:text-gray-700 transition-colors"
          title="刷新后端状态"
        >
          &#x21bb;
        </button>
      </div>

      {/* Model name: only shown when the backend actually reported one */}
      {backendStatus.modelName ? (
        <div className="px-2.5 py-1 rounded-full bg-gray-100 border border-gray-200 text-gray-700">
          {backendStatus.modelName}
        </div>
      ) : (
        <div className="px-2.5 py-1 rounded-full bg-gray-100 border border-gray-200 text-gray-400">
          Model Unknown
        </div>
      )}

      {/* Authentication state */}
      <div
        className={`px-2.5 py-1 rounded-full bg-gray-100 border ${
          backendStatus.authStatus === "authenticated"
            ? "border-gray-300 text-gray-900"
            : backendStatus.authStatus === "unauthenticated"
              ? "border-gray-300 text-gray-500"
              : "border-gray-200 text-gray-400"
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
    <div className="mx-auto max-w-2xl mt-4 px-4 py-3 rounded-2xl bg-gray-50 border border-gray-200 text-gray-600 text-sm flex items-center gap-3">
      <span className="text-lg text-gray-400">&#x26A0;</span>
      <div className="flex-1">
        {unauthenticated ? (
          <>
            <span className="font-bold text-gray-900">后端鉴权失败。</span>
            {" "}服务器拒绝了当前请求（401/403），请检查访问凭证配置后重试。
          </>
        ) : (
          <>
            <span className="font-bold text-gray-900">后端未连接。</span>
            {" "}当前无法使用实时导航功能，请检查后端服务是否正常运行。
          </>
        )}
      </div>
    </div>
  );
}
