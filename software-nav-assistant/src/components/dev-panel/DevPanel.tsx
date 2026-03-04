"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";

export function DevPanel() {
  const { context, currentDecision, isLoading } = useTaskContext();

  return (
    <div className="h-full bg-slate-950 text-emerald-400 font-mono text-xs overflow-y-auto flex flex-col rounded-[2.5rem] border-[8px] border-slate-900 shadow-2xl custom-scrollbar">
      <div className="p-5 border-b border-slate-800 flex justify-between items-center sticky top-0 bg-slate-950/90 backdrop-blur z-10">
        <h3 className="text-white font-bold flex items-center gap-2 text-sm">
          <span className={`w-3 h-3 rounded-full ${isLoading ? 'bg-blue-500 animate-pulse' : 'bg-emerald-500'}`}></span>
          Agent Trace Log (黑匣子)
        </h3>
        <span className={`px-2 py-1 rounded font-black ${
          context.state === 'RISK_PAUSED' ? 'bg-red-900 text-red-400 animate-pulse' : 'bg-emerald-900/50 text-emerald-400'
        }`}>
          FSM: {context.state}
        </span>
      </div>

      <div className="p-5 space-y-5 flex-1">
        {/* 1. 全局状态追踪 */}
        <section className="bg-slate-900 p-4 rounded-xl border border-slate-800">
          <h4 className="text-slate-500 mb-2 tracking-widest uppercase font-bold">1. Context Tracker</h4>
          <div className="grid grid-cols-2 gap-2">
            <div><span className="text-slate-400">Session:</span> {context.session_id.split('-')[0]}...</div>
            <div><span className="text-slate-400">Step:</span> <span className="text-blue-400 font-bold">{context.current_step_index}</span></div>
            <div className="col-span-2 truncate"><span className="text-slate-400">Goal:</span> {context.global_goal}</div>
          </div>
        </section>

        {!currentDecision && !isLoading && (
          <div className="text-slate-600 text-center py-10 border border-dashed border-slate-800 rounded-xl">
             Waiting for screenshot input...
          </div>
        )}

        {currentDecision && (
          <div className="space-y-5 animate-in slide-in-from-bottom-2 duration-500">
            {/* 2. 验证器引擎（防偏航） */}
            <section className="bg-slate-900 p-4 rounded-xl border border-slate-800">
              <h4 className="text-slate-500 mb-2 tracking-widest uppercase font-bold">2. Verifier Engine</h4>
              <div className="space-y-1">
                <p><span className="text-slate-400">P_SUCCESS:</span> {currentDecision.verification.is_previous_step_successful ? '✅ TRUE' : '❌ FALSE'}</p>
                <p><span className="text-slate-400">DEVIATION:</span> {currentDecision.verification.is_deviation ? '⚠️ TRUE' : '✅ FALSE'}</p>
                <p className="mt-2 text-emerald-200 bg-black/30 p-2 rounded leading-relaxed">
                  <span className="text-slate-500">REASON: </span>
                  {currentDecision.verification.reasoning}
                </p>
              </div>
            </section>

            {/* 3. 安全守卫（物理熔断） */}
            <section className="bg-slate-900 p-4 rounded-xl border border-slate-800">
               <h4 className="text-slate-500 mb-2 tracking-widest uppercase font-bold">3. Safety Guard</h4>
               <div className="flex gap-4">
                 <div className={`px-2 py-1 rounded ${currentDecision.screen_state.risk_detected ? 'bg-red-900/50 text-red-400 border border-red-800/50 font-bold' : 'bg-black/30 text-slate-400'}`}>
                    Detect: {currentDecision.screen_state.risk_detected ? 'HIGH_RISK 🚨' : 'SAFE'}
                 </div>
                 <div className={`px-2 py-1 rounded ${currentDecision.next_step.risk_level === 'HIGH' ? 'bg-red-900/50 text-red-400 font-bold' : 'bg-black/30 text-slate-400'}`}>
                    Risk Level: {currentDecision.next_step.risk_level}
                 </div>
               </div>
            </section>

            {/* 4. CoT 与动作规划 */}
            <section className="bg-slate-900 p-4 rounded-xl border border-slate-800 border-l-2 border-l-blue-500">
              <h4 className="text-slate-500 mb-2 tracking-widest uppercase font-bold">4. Planner (CoT)</h4>
              <div className="text-indigo-300 mb-3 leading-relaxed italic">
                &quot;{currentDecision.audit_metadata.reasoning_trace}&quot;
              </div>
              <div className="bg-black p-3 rounded space-y-1">
                <div><span className="text-blue-400">intent:</span> <span className="text-yellow-300">&quot;{currentDecision.next_step.intent}&quot;</span></div>
                <div><span className="text-blue-400">target:</span> <span className="text-yellow-300">&quot;{currentDecision.next_step.target_element_desc}&quot;</span></div>
                <div><span className="text-blue-400">bbox:</span> <span className="text-orange-300">{JSON.stringify(currentDecision.next_step.target_bbox)}</span></div>
              </div>
            </section>

            {/* 5. 下一步预期标尺 */}
            <section className="bg-slate-900 p-4 rounded-xl border border-slate-800 text-cyan-300">
               <span className="text-slate-500 font-bold uppercase">5. Next Checkpoint:</span><br/>
               {currentDecision.next_screen_checkpoint.expected_page_type}
            </section>
          </div>
        )}
      </div>
    </div>
  );
}
