"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import { TaskProvider, useTaskContext } from "@/lib/context/TaskProvider";
import { ScreenRenderer } from "@/components/elderly-ui/ScreenRenderer";
import { GuidanceCard } from "@/components/elderly-ui/GuidanceCard";
import { DevPanel } from "@/components/dev-panel/DevPanel";
import { GoalChatPanel, GoalPlan } from "@/components/chat/GoalChatPanel";
import { Smartphone, RefreshCw, Play, Square } from "lucide-react";
import { StatusIndicator, DemoModeWarningBanner } from "@/components/status-indicator/StatusIndicator";

// ==========================================
// Core view: hooks can only be used when wrapped by the Provider
// ==========================================
function MainInterface() {
  const { submitNewScreen, isLoading, context, resetSession, demoMode } = useTaskContext();
  const [isGuideStarted, setIsGuideStarted] = useState(false);
  const [isAutoCaptureRunning, setIsAutoCaptureRunning] = useState(false);
  const streamRef = useRef<MediaStream | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const captureTimerRef = useRef<number | null>(null);
  const isSubmittingRef = useRef(false);
  const isLoadingRef = useRef(isLoading);
  const submitNewScreenRef = useRef(submitNewScreen);

  useEffect(() => {
    isLoadingRef.current = isLoading;
  }, [isLoading]);

  useEffect(() => {
    submitNewScreenRef.current = submitNewScreen;
  }, [submitNewScreen]);

  const stopAutoCapture = useCallback(() => {
    if (captureTimerRef.current !== null) {
      window.clearInterval(captureTimerRef.current);
      captureTimerRef.current = null;
    }

    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => {
        track.stop();
      });
      streamRef.current = null;
    }

    if (videoRef.current) {
      videoRef.current.pause();
      videoRef.current.srcObject = null;
      videoRef.current = null;
    }

    isSubmittingRef.current = false;
    setIsAutoCaptureRunning(false);
  }, []);

  const captureAndUpload = useCallback(async () => {
    const video = videoRef.current;
    if (!video || video.readyState < 2) return;
    if (isSubmittingRef.current || isLoadingRef.current) return;

    const { videoWidth, videoHeight } = video;
    if (!videoWidth || !videoHeight) return;

    isSubmittingRef.current = true;
    try {
      const maxDimension = 800;
      let targetWidth = videoWidth;
      let targetHeight = videoHeight;

      if (targetWidth > targetHeight && targetWidth > maxDimension) {
        targetHeight = Math.round((targetHeight * maxDimension) / targetWidth);
        targetWidth = maxDimension;
      } else if (targetHeight >= targetWidth && targetHeight > maxDimension) {
        targetWidth = Math.round((targetWidth * maxDimension) / targetHeight);
        targetHeight = maxDimension;
      }

      const canvas = document.createElement("canvas");
      canvas.width = targetWidth;
      canvas.height = targetHeight;

      const ctx = canvas.getContext("2d");
      if (!ctx) throw new Error("Canvas 初始化失败");

      ctx.fillStyle = "#FFFFFF";
      ctx.fillRect(0, 0, targetWidth, targetHeight);
      ctx.drawImage(video, 0, 0, targetWidth, targetHeight);

      const base64 = canvas.toDataURL("image/jpeg", 0.7);
      await submitNewScreenRef.current(base64);
    } catch (error) {
      console.error("自动截图上传失败:", error);
    } finally {
      isSubmittingRef.current = false;
    }
  }, []);

  const startAutoCapture = useCallback(async () => {
    if (isAutoCaptureRunning) return;

    if (!navigator.mediaDevices?.getDisplayMedia) {
      alert("当前浏览器不支持屏幕捕获，请更换 Chrome/Edge 最新版本。");
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: {
          frameRate: { ideal: 10, max: 15 }
        },
        audio: false,
      });

      const videoTrack = stream.getVideoTracks()[0];
      if (!videoTrack) throw new Error("未获取到屏幕视频轨道");
      videoTrack.addEventListener("ended", stopAutoCapture);

      const video = document.createElement("video");
      video.srcObject = stream;
      video.muted = true;
      video.playsInline = true;
      await video.play();

      streamRef.current = stream;
      videoRef.current = video;
      setIsAutoCaptureRunning(true);

      await captureAndUpload();
      captureTimerRef.current = window.setInterval(() => {
        void captureAndUpload();
      }, 1000);
    } catch (error) {
      console.error("启动自动截图失败:", error);
      alert("屏幕共享未启动成功，请重试并在弹窗中选择要共享的窗口。");
      stopAutoCapture();
    }
  }, [captureAndUpload, isAutoCaptureRunning, stopAutoCapture]);

  useEffect(() => {
    return () => {
      stopAutoCapture();
    };
  }, [stopAutoCapture]);

  const handleStartGuide = useCallback((plan: GoalPlan) => {
    const combinedGoal = `目标App: ${plan.targetAppName}；任务: ${plan.inferredGoal}`;
    resetSession(combinedGoal);
    setIsGuideStarted(true);
  }, [resetSession]);

  const handleResetAll = useCallback(() => {
    stopAutoCapture();
    resetSession();
    setIsGuideStarted(false);
  }, [resetSession, stopAutoCapture]);

  return (
    <div className="flex flex-col h-full bg-slate-50 rounded-[3rem] p-6 lg:p-10 shadow-2xl relative overflow-y-auto border border-slate-200">

      {/* 顶部控制栏 */}
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-black text-slate-800 flex items-center gap-2 tracking-tight">
          <Smartphone className="w-8 h-8 text-blue-600" />
          亲情导航向导 <span className="bg-blue-100 text-blue-600 text-xs px-2 py-1 rounded-full font-bold ml-2">Beta</span>
          {demoMode && <span className="bg-amber-100 text-amber-700 text-xs px-2 py-1 rounded-full font-bold ml-1">Demo Mode</span>}
        </h1>
        <button
          onClick={handleResetAll}
          className="flex items-center gap-2 px-4 py-2 bg-slate-200 text-slate-600 rounded-full font-bold active:bg-slate-300 hover:bg-slate-300 transition-colors text-sm"
        >
          <RefreshCw className="w-4 h-4" />
          重置会话
        </button>
      </div>

      {!isGuideStarted ? (
        <div className="flex-1 w-full max-w-2xl mx-auto flex flex-col">
          <GoalChatPanel onStartGuide={handleStartGuide} />
          <p className="text-center text-slate-400 text-xs mt-4">
            对话确认后会出现“开始引导”模块，点击后进入自动截图导航模式。
          </p>
        </div>
      ) : (
        <>
          {/* 视觉渲染与长辈交互区 */}
          <div className="flex-1 w-full max-w-md mx-auto flex flex-col">
            <ScreenRenderer />
            <GuidanceCard />
          </div>

          {/* 底部交互区：启动屏幕共享后，每秒自动截图上传 */}
          <div className="w-full max-w-md mx-auto mt-8">
            <div className="text-center text-slate-400 font-bold text-sm mb-3">👇 [演示控制台] 启动后每 1 秒自动上传一帧截图</div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <button
                onClick={startAutoCapture}
                disabled={isAutoCaptureRunning || context.state === "RISK_PAUSED"}
                className={`w-full py-5 rounded-3xl text-xl font-black flex items-center justify-center gap-3 transition-all duration-300 shadow-lg active:scale-95
                  ${isAutoCaptureRunning
                    ? "bg-blue-300 text-blue-50 cursor-not-allowed"
                    : context.state === "RISK_PAUSED"
                      ? "bg-slate-300 text-slate-500 cursor-not-allowed"
                      : "bg-blue-600 text-white hover:bg-blue-700 hover:shadow-xl hover:-translate-y-1"}`}
              >
                <Play className="w-7 h-7" />
                {isAutoCaptureRunning ? "自动上传中..." : "开始每秒自动截图"}
              </button>
              <button
                onClick={stopAutoCapture}
                disabled={!isAutoCaptureRunning}
                className={`w-full py-5 rounded-3xl text-xl font-black flex items-center justify-center gap-3 transition-all duration-300 shadow-lg active:scale-95
                  ${isAutoCaptureRunning
                    ? "bg-rose-600 text-white hover:bg-rose-700 hover:shadow-xl hover:-translate-y-1"
                    : "bg-slate-300 text-slate-500 cursor-not-allowed"}`}
              >
                <Square className="w-7 h-7" />
                停止自动截图
              </button>
            </div>
            <p className="text-center text-slate-400 text-xs mt-3">
              首次启动会弹出浏览器权限窗口，请选择要共享的手机投屏窗口或屏幕。
            </p>
          </div>
        </>
      )}
    </div>
  );
}

export default function Page() {
  return (
    <TaskProvider>
      <main className="min-h-screen bg-slate-900 p-4 lg:p-6 font-sans flex flex-col">

        {/* Hackathon 专属头部 */}
        <header className="mb-6 text-center lg:text-left shrink-0 pl-2 mt-2">
          <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3">
            <div>
              <h1 className="text-3xl font-black text-white tracking-tight">Project: Safe Nav Agent</h1>
              <p className="text-blue-400 font-mono mt-2 text-sm">Defensive Architecture &bull; Human-in-the-loop &bull; Verification First</p>
            </div>
            <StatusIndicator />
          </div>
          <DemoModeWarningBanner />
        </header>

        {/* 双栏网格：左侧产品体验，右侧评委黑箱 */}
        <div className="flex-1 grid grid-cols-1 lg:grid-cols-[1fr_400px] xl:grid-cols-[1fr_500px] gap-8 min-h-0">
          <div className="h-full min-h-[750px] max-w-3xl mx-auto w-full">
            <MainInterface />
          </div>

          <div className="hidden lg:block h-[calc(100vh-8rem)] sticky top-4">
            <DevPanel />
          </div>
        </div>
      </main>
    </TaskProvider>
  );
}
