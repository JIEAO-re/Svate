"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import { TaskProvider, useTaskContext } from "@/lib/context/TaskProvider";
import { ScreenRenderer } from "@/components/elderly-ui/ScreenRenderer";
import { GuidanceCard } from "@/components/elderly-ui/GuidanceCard";
import { DevPanel } from "@/components/dev-panel/DevPanel";
import { GoalChatPanel, GoalPlan } from "@/components/chat/GoalChatPanel";
import { Smartphone, RefreshCw, Play, Square } from "lucide-react";
import { StatusIndicator, BackendStatusBanner } from "@/components/status-indicator/StatusIndicator";

// ==========================================
// Core view: hooks can only be used when wrapped by the Provider
// ==========================================
function MainInterface() {
  const { submitNewScreen, isLoading, context, resetSession } = useTaskContext();
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
    <div className="flex flex-col h-full bg-white rounded-3xl border border-gray-200 shadow-sm p-6 lg:p-10 relative overflow-y-auto text-gray-900">

      {/* Top control bar */}
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-black text-gray-900 flex items-center gap-2 tracking-tight">
          <Smartphone className="w-7 h-7 text-gray-900" />
          通用 Agent <span className="bg-gray-100 text-gray-500 text-xs px-2.5 py-1 rounded-full font-bold ml-2">Beta</span>
        </h1>
        <button
          onClick={handleResetAll}
          className="flex items-center gap-2 px-4 py-2 rounded-full bg-gray-100 text-gray-600 font-bold hover:bg-gray-200 transition-colors text-sm"
        >
          <RefreshCw className="w-4 h-4" />
          重置会话
        </button>
      </div>

      {!isGuideStarted ? (
        <div className="flex-1 w-full max-w-2xl mx-auto flex flex-col">
          <GoalChatPanel onStartGuide={handleStartGuide} />
          <p className="text-center text-gray-400 text-xs mt-4">
            对话确认后会出现“开始引导”模块，点击后进入自动截图导航模式。
          </p>
        </div>
      ) : (
        <>
          {/* Visual rendering and elderly interaction area */}
          <div className="flex-1 w-full max-w-md mx-auto flex flex-col">
            <ScreenRenderer />
            <GuidanceCard />
          </div>

          {/* Bottom controls: after screen sharing starts, auto-capture and upload one frame per second */}
          <div className="w-full max-w-md mx-auto mt-8">
            <div className="text-center text-gray-400 font-bold text-sm mb-3">[演示控制台] 启动后每 1 秒自动上传一帧截图</div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <button
                onClick={startAutoCapture}
                disabled={isAutoCaptureRunning || context.state === "RISK_PAUSED"}
                className={`w-full py-5 rounded-3xl text-xl font-black flex items-center justify-center gap-3 transition-all duration-300 active:scale-95
                  ${isAutoCaptureRunning
                    ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                    : context.state === "RISK_PAUSED"
                      ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                      : "bg-black text-white shadow-sm hover:bg-gray-800 hover:-translate-y-0.5"}`}
              >
                <Play className="w-7 h-7" />
                {isAutoCaptureRunning ? "自动上传中..." : "开始每秒自动截图"}
              </button>
              <button
                onClick={stopAutoCapture}
                disabled={!isAutoCaptureRunning}
                className={`w-full py-5 rounded-3xl text-xl font-black flex items-center justify-center gap-3 transition-all duration-300 active:scale-95
                  ${isAutoCaptureRunning
                    ? "bg-white text-gray-900 border border-gray-300 hover:bg-gray-50 hover:-translate-y-0.5"
                    : "bg-gray-100 text-gray-400 cursor-not-allowed"}`}
              >
                <Square className="w-7 h-7" />
                停止自动截图
              </button>
            </div>
            <p className="text-center text-gray-400 text-xs mt-3">
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
      <main className="min-h-screen p-4 lg:p-6 font-sans flex flex-col text-gray-900">

        {/* Header */}
        <header className="mb-6 text-center lg:text-left shrink-0 pl-2 mt-2">
          <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3">
            <div>
              <h1 className="text-3xl font-black text-gray-900 tracking-tight">Svate · 通用手机 Agent</h1>
              <p className="text-gray-400 font-mono mt-2 text-sm">Universal Mobile Agent &bull; Human-in-the-loop &bull; Verification First</p>
            </div>
            <StatusIndicator />
          </div>
          <BackendStatusBanner />
        </header>

        {/* Two-column grid: product experience on the left, judge black box on the right */}
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
