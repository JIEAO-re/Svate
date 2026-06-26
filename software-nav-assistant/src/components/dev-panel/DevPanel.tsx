"use client";

import React from "react";
import { useTaskContext } from "@/lib/context/TaskProvider";

// Debug panel that mirrors the raw /api/mobile-agent/next-step response.
// Every value under sections 2-5 comes verbatim from the backend payload;
// section 1 is client-side session state and is labeled as such.
export function DevPanel() {
  const { context, currentTurn, isLoading } = useTaskContext();

  return (
    <div className="h-full bg-white border border-gray-200 shadow-sm font-mono text-xs overflow-y-auto flex flex-col rounded-3xl text-gray-700 custom-scrollbar">
      <div className="p-5 border-b border-gray-200 flex justify-between items-center sticky top-0 bg-white/90 backdrop-blur z-10">
        <h3 className="text-gray-900 font-bold flex items-center gap-2 text-sm">
          <span className={`w-3 h-3 rounded-full ${isLoading ? 'bg-gray-900 animate-pulse' : 'bg-gray-400'}`}></span>
          Agent Trace Log (黑匣子)
        </h3>
        <span className={`px-2 py-1 rounded font-black ${
          context.state === 'RISK_PAUSED' ? 'bg-gray-900 text-white animate-pulse' : 'bg-gray-100 text-gray-600'
        }`}>
          FSM: {context.state}
        </span>
      </div>

      <div className="p-5 space-y-5 flex-1">
        {/* 1. Client-side session state (maintained by the browser, not the backend) */}
        <section className="bg-gray-50 p-4 rounded-xl border border-gray-200">
          <h4 className="text-gray-400 mb-2 tracking-widest uppercase font-bold">
            1. Context Tracker <span className="text-gray-400 normal-case tracking-normal">(client state)</span>
          </h4>
          <div className="grid grid-cols-2 gap-2">
            <div><span className="text-gray-500">Session:</span> {context.session_id.split('-')[0]}...</div>
            <div><span className="text-gray-500">Step:</span> <span className="text-gray-900 font-bold">{context.current_step_index}</span></div>
            <div className="col-span-2 truncate"><span className="text-gray-500">Goal:</span> {context.global_goal}</div>
          </div>
        </section>

        {!currentTurn && !isLoading && (
          <div className="text-gray-400 text-center py-10 border border-dashed border-gray-300 rounded-xl">
             Waiting for screenshot input...
          </div>
        )}

        {currentTurn && (
          <div className="space-y-5 animate-in slide-in-from-bottom-2 duration-500">
            {/* 2. Reviewer output exactly as returned by the backend */}
            <section className="bg-gray-50 p-4 rounded-xl border border-gray-200">
              <h4 className="text-gray-400 mb-2 tracking-widest uppercase font-bold">2. Reviewer</h4>
              <div className="space-y-1">
                <p>
                  <span className="text-gray-500">VERDICT:</span>{" "}
                  <span className={currentTurn.reviewer.verdict === "APPROVE" ? "text-gray-900 font-bold" : "text-gray-500 font-bold"}>
                    {currentTurn.reviewer.verdict}
                  </span>
                </p>
                <p><span className="text-gray-500">MODEL:</span> {currentTurn.reviewer.model} ({currentTurn.reviewer.latency_ms}ms)</p>
                <p className="mt-2 text-gray-700 bg-gray-100 p-2 rounded leading-relaxed">
                  <span className="text-gray-400">REASON: </span>
                  {currentTurn.reviewer.reason}
                </p>
              </div>
            </section>

            {/* 3. Safety guard verdict as returned by the backend */}
            <section className="bg-gray-50 p-4 rounded-xl border border-gray-200">
               <h4 className="text-gray-400 mb-2 tracking-widest uppercase font-bold">3. Safety Guard</h4>
               <div className="flex gap-4 flex-wrap">
                 <div className={`px-2 py-1 rounded ${currentTurn.guard.risk_level === 'HIGH' ? 'bg-gray-900 text-white font-bold' : 'bg-gray-100 text-gray-500'}`}>
                    Guard: {currentTurn.guard.risk_level === 'HIGH' ? 'HIGH_RISK' : currentTurn.guard.risk_level}
                 </div>
                 <div className={`px-2 py-1 rounded ${currentTurn.final_action.risk_level === 'HIGH' ? 'bg-gray-900 text-white font-bold' : 'bg-gray-100 text-gray-500'}`}>
                    Action Risk: {currentTurn.final_action.risk_level}
                 </div>
               </div>
               {currentTurn.guard.block_reason && (
                 <p className="mt-2 text-gray-900 bg-gray-100 p-2 rounded leading-relaxed">
                   <span className="text-gray-400">BLOCK: </span>
                   {currentTurn.guard.block_reason}
                 </p>
               )}
            </section>

            {/* 4. Planner output and the approved final action */}
            <section className="bg-gray-50 p-4 rounded-xl border border-gray-200 border-l-2 border-l-gray-900">
              <h4 className="text-gray-400 mb-2 tracking-widest uppercase font-bold">4. Planner</h4>
              <div className="text-gray-500 mb-3">
                {currentTurn.planner.model} ({currentTurn.planner.latency_ms}ms) &bull; {currentTurn.planner.candidates.length} candidate{currentTurn.planner.candidates.length === 1 ? "" : "s"}
              </div>
              <div className="bg-gray-900 p-3 rounded space-y-1">
                <div><span className="text-gray-400">intent:</span> <span className="text-gray-100">&quot;{currentTurn.final_action.intent}&quot;</span></div>
                <div><span className="text-gray-400">target:</span> <span className="text-gray-100">&quot;{currentTurn.final_action.target_desc}&quot;</span></div>
                {currentTurn.final_action.spatial_coordinates != null && (
                  <div><span className="text-gray-400">spatial:</span> <span className="text-gray-300">{JSON.stringify(currentTurn.final_action.spatial_coordinates)}</span></div>
                )}
                {currentTurn.final_action.target_bbox != null && (
                  <div><span className="text-gray-400">bbox:</span> <span className="text-gray-300">{JSON.stringify(currentTurn.final_action.target_bbox)}</span></div>
                )}
              </div>
              <div className="mt-3 text-gray-600 leading-relaxed italic">
                &quot;{currentTurn.final_action.narration}&quot;
              </div>
            </section>

            {/* 5. Checkpoint the backend expects on the next screen */}
            <section className="bg-gray-50 p-4 rounded-xl border border-gray-200 text-gray-600">
               <span className="text-gray-400 font-bold uppercase">5. Next Checkpoint:</span><br/>
               {currentTurn.checkpoint.expected_page_type || "(none)"}
               {currentTurn.checkpoint.expected_elements.length > 0 && (
                 <span className="text-gray-400"> &bull; {currentTurn.checkpoint.expected_elements.join(", ")}</span>
               )}
            </section>

            {/* Server trace id for cross-referencing backend logs */}
            <p className="text-gray-400 truncate">trace: {currentTurn.trace_id}</p>
          </div>
        )}
      </div>
    </div>
  );
}
