"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";
import { SessionState } from "@/lib/schemas/state-machine";

export function ScreenRenderer() {
  const { latestImageBase64, currentDecision, context, isLoading } = useTaskContext();

  // Parse AI-provided coordinates in [ymin, xmin, ymax, xmax] format (0-1000)
  const bbox = currentDecision?.next_step?.target_bbox;

  // Draw boxes only when the status is explicit and no high-risk state is active
  const shouldRenderBox =
    bbox &&
    bbox.length === 4 &&
    !isLoading &&
    context.state !== SessionState.RISK_PAUSED &&
    currentDecision?.next_step?.risk_level !== "HIGH";

  const lockAnimationKey = shouldRenderBox
    ? `${bbox[0]}_${bbox[1]}_${bbox[2]}_${bbox[3]}_${context.current_step_index}`
    : "no-lock";
  const lockLabel = currentDecision?.next_step?.target_element_desc || "请点这里";

  return (
    <div className="relative w-full max-w-[400px] mx-auto aspect-[9/19.5] bg-gray-900 rounded-[2.5rem] border-[10px] border-gray-800 overflow-hidden shadow-2xl flex items-center justify-center">

      {!latestImageBase64 ? (
        <div className="text-gray-500 text-center p-8">
          <p className="text-6xl mb-4 text-gray-400">📱</p>
          <p className="text-xl font-bold">请上传手机截图<br/>开始导航</p>
        </div>
      ) : (
        <div className="relative w-full h-full">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={latestImageBase64}
            alt="长辈手机屏幕"
            className={`w-full h-full object-contain transition-all duration-300 ${isLoading ? 'opacity-40 blur-[2px] grayscale' : 'opacity-100'}`}
          />

          {/* 🌟 核心：大模型目标区域高亮呼吸框 */}
          {shouldRenderBox && (
            <div
              key={lockAnimationKey}
              className="absolute pointer-events-none z-10 transition-all duration-300 lock-target-frame"
              style={{
                // Coordinate conversion: Gemini uses (ymin, xmin, ymax, xmax) in the 0-1000 range
                top: `${(bbox[0] / 10) }%`,
                left: `${(bbox[1] / 10) }%`,
                height: `${((bbox[2] - bbox[0]) / 10) }%`,
                width: `${((bbox[3] - bbox[1]) / 10) }%`,
              }}
            >
              <div className="absolute left-1/2 -translate-x-1/2 -top-28 h-24 w-1.5 bg-gradient-to-b from-fuchsia-300 via-cyan-300 to-transparent rounded-full" />

              <div className="absolute -inset-2 rounded-2xl p-[7px] lock-rainbow-border shadow-[0_0_36px_rgba(59,130,246,0.42)]">
                <div className="w-full h-full rounded-xl bg-black/15 border border-white/25 backdrop-blur-[1px]" />
              </div>

              {/* 视觉锚点：大号指示标 */}
              <div className="absolute -bottom-14 left-1/2 -translate-x-1/2 bg-black/75 text-white px-5 py-2 rounded-full font-black text-lg shadow-xl whitespace-nowrap">
                👇 {lockLabel}
              </div>
            </div>
          )}

          {/* AI 分析时的全屏雷达扫描动画 */}
          {isLoading && (
            <div className="absolute inset-0 z-20 pointer-events-none overflow-hidden flex flex-col items-center justify-center">
               <div className="w-full h-2 bg-blue-500/80 shadow-[0_0_20px_theme(colors.blue.400)] absolute left-0 animate-[scan_2s_ease-in-out_infinite]" />
               <div className="bg-black/80 text-white text-xl font-bold px-6 py-3 rounded-full mt-10 tracking-widest">
                 小助手正在看屏幕...
               </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
