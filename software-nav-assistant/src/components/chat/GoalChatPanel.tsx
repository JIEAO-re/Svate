"use client";

import React, { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Mic, MicOff, MessageCircle, Send, Sparkles } from "lucide-react";

type ChatRole = "user" | "assistant";

interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
}

export interface GoalPlan {
  inferredGoal: string;
  targetAppName: string;
}

interface ChatGoalPanelProps {
  onStartGuide: (plan: GoalPlan) => void;
}

interface ChatGoalApiResponse {
  success: boolean;
  reply: string;
  inferred_goal: string;
  target_app_name: string;
  ready_to_start: boolean;
  error?: string;
}

interface BrowserSpeechRecognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: unknown) => void) | null;
  onerror: ((event: unknown) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
}

function createId(): string {
  return `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

function normalizeTargetAppName(name: string): string {
  const trimmed = name.trim();
  return trimmed.length > 0 ? trimmed : "目标应用";
}

export function GoalChatPanel({ onStartGuide }: ChatGoalPanelProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: createId(),
      role: "assistant",
      content: "您好，我是导航助手。请告诉我您想打开哪个应用、要完成什么操作，例如“打开微信给小明打视频”。",
    },
  ]);
  const [inputValue, setInputValue] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [isSpeechSupported, setIsSpeechSupported] = useState(false);
  const [readyPlan, setReadyPlan] = useState<GoalPlan | null>(null);
  const [errorText, setErrorText] = useState<string | null>(null);

  const listRef = useRef<HTMLDivElement | null>(null);
  const recognitionRef = useRef<BrowserSpeechRecognition | null>(null);

  useEffect(() => {
    if (!listRef.current) return;
    listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [messages, readyPlan]);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const SpeechRecognitionCtor = (
      window as unknown as {
        SpeechRecognition?: new () => BrowserSpeechRecognition;
        webkitSpeechRecognition?: new () => BrowserSpeechRecognition;
      }
    ).SpeechRecognition || (
      window as unknown as {
        SpeechRecognition?: new () => BrowserSpeechRecognition;
        webkitSpeechRecognition?: new () => BrowserSpeechRecognition;
      }
    ).webkitSpeechRecognition;

    if (!SpeechRecognitionCtor) return;
    setIsSpeechSupported(true);

    const recognition = new SpeechRecognitionCtor();
    recognition.lang = "zh-CN";
    recognition.interimResults = true;
    recognition.continuous = false;
    recognition.onresult = (event: unknown) => {
      const resultEvent = event as { results?: ArrayLike<{ 0?: { transcript?: string } }> };
      const allText = Array.from(resultEvent.results ?? [])
        .map((item) => item?.[0]?.transcript ?? "")
        .join("")
        .trim();
      setInputValue(allText);
    };
    recognition.onerror = () => {
      setIsListening(false);
    };
    recognition.onend = () => {
      setIsListening(false);
    };

    recognitionRef.current = recognition;
    return () => {
      recognition.stop();
      recognitionRef.current = null;
    };
  }, []);

  const speakAssistantReply = useCallback((text: string) => {
    if (typeof window === "undefined") return;
    if (!("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "zh-CN";
    utterance.rate = 1;
    window.speechSynthesis.speak(utterance);
  }, []);

  const chatPayloadMessages = useMemo(
    () => messages.map((message) => ({ role: message.role, content: message.content })),
    [messages],
  );

  const sendMessage = useCallback(async (content: string) => {
    const trimmed = content.trim();
    if (!trimmed || isSending) return;

    setErrorText(null);
    setReadyPlan(null);

    const userMessage: ChatMessage = {
      id: createId(),
      role: "user",
      content: trimmed,
    };
    const nextMessages = [...messages, userMessage];
    setMessages(nextMessages);
    setInputValue("");
    setIsSending(true);

    try {
      const response = await fetch("/api/chat-goal", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          messages: nextMessages.map((msg) => ({ role: msg.role, content: msg.content })),
        }),
      });

      const data = (await response.json()) as ChatGoalApiResponse;
      if (!response.ok || !data.success) {
        throw new Error(data.error || "对话服务异常");
      }

      const assistantText = data.reply?.trim() || "我收到了，我们继续。";
      setMessages((prev) => [
        ...prev,
        { id: createId(), role: "assistant", content: assistantText },
      ]);
      speakAssistantReply(assistantText);

      if (data.ready_to_start) {
        setReadyPlan({
          inferredGoal: data.inferred_goal,
          targetAppName: normalizeTargetAppName(data.target_app_name),
        });
      }
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : "未知错误";
      setErrorText(message);
    } finally {
      setIsSending(false);
    }
  }, [isSending, messages, speakAssistantReply]);

  const handleSubmit = useCallback(
    async (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      await sendMessage(inputValue);
    },
    [inputValue, sendMessage],
  );

  const toggleVoiceInput = useCallback(() => {
    if (!recognitionRef.current) return;
    if (isListening) {
      recognitionRef.current.stop();
      setIsListening(false);
      return;
    }
    setErrorText(null);
    recognitionRef.current.start();
    setIsListening(true);
  }, [isListening]);

  return (
    <section className="w-full rounded-3xl border border-slate-200 bg-white shadow-xl overflow-hidden">
      <div className="border-b border-slate-200 px-5 py-4 bg-gradient-to-r from-cyan-50 via-sky-50 to-indigo-50">
        <h2 className="text-lg font-black text-slate-800 flex items-center gap-2">
          <MessageCircle className="w-5 h-5 text-blue-600" />
          先聊目标，再开始引导
        </h2>
        <p className="text-sm text-slate-500 mt-1">告诉我应用和任务，确认后会出现“开始引导”模块。</p>
      </div>

      <div ref={listRef} className="h-[380px] overflow-y-auto px-4 py-4 space-y-3 custom-scrollbar bg-slate-50">
        {messages.map((message) => (
          <div
            key={message.id}
            className={`max-w-[88%] rounded-2xl px-4 py-3 text-sm leading-relaxed shadow-sm ${
              message.role === "assistant"
                ? "bg-white text-slate-700 border border-slate-200"
                : "ml-auto bg-blue-600 text-white"
            }`}
          >
            {message.content}
          </div>
        ))}

        {readyPlan && (
          <div className="rounded-2xl border-2 border-emerald-300 bg-emerald-50 p-4 shadow-sm">
            <div className="flex items-center gap-2 text-emerald-700 font-black mb-2">
              <Sparkles className="w-4 h-4" />
              开始引导
            </div>
            <p className="text-sm text-emerald-900">目标应用：{readyPlan.targetAppName}</p>
            <p className="text-sm text-emerald-900 mt-1">任务：{readyPlan.inferredGoal}</p>
            <button
              onClick={() => onStartGuide(readyPlan)}
              className="mt-3 w-full rounded-xl bg-emerald-600 text-white py-2.5 font-bold hover:bg-emerald-700 transition-colors"
            >
              开始引导并关闭对话
            </button>
          </div>
        )}
      </div>

      <form onSubmit={handleSubmit} className="border-t border-slate-200 p-4 bg-white">
        <div className="flex gap-2">
          <button
            type="button"
            onClick={toggleVoiceInput}
            disabled={!isSpeechSupported || isSending}
            className={`h-11 w-11 shrink-0 rounded-xl border flex items-center justify-center transition-colors ${
              isListening
                ? "bg-rose-100 border-rose-300 text-rose-600"
                : "bg-slate-100 border-slate-200 text-slate-500"
            } ${!isSpeechSupported || isSending ? "opacity-50 cursor-not-allowed" : ""}`}
            title={isSpeechSupported ? "语音输入" : "当前浏览器不支持语音输入"}
          >
            {isListening ? <MicOff className="w-5 h-5" /> : <Mic className="w-5 h-5" />}
          </button>
          <input
            value={inputValue}
            onChange={(event) => setInputValue(event.target.value)}
            placeholder="例如：打开微信，给小明发视频通话"
            className="flex-1 h-11 rounded-xl border border-slate-200 px-3 text-sm outline-none focus:ring-2 focus:ring-blue-200 focus:border-blue-300"
            disabled={isSending}
          />
          <button
            type="submit"
            disabled={isSending || inputValue.trim().length === 0}
            className="h-11 px-4 rounded-xl bg-blue-600 text-white font-bold disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
          >
            <Send className="w-4 h-4" />
            发送
          </button>
        </div>

        <div className="mt-2 min-h-5 text-xs">
          {errorText ? <span className="text-rose-600">对话失败：{errorText}</span> : null}
          {!errorText && isListening ? <span className="text-blue-600">正在听您说话...</span> : null}
          {!errorText && !isListening && !isSpeechSupported ? (
            <span className="text-slate-400">当前浏览器不支持语音输入，可直接打字。</span>
          ) : null}
          {!errorText && !isListening && isSpeechSupported && chatPayloadMessages.length > 0 ? (
            <span className="text-slate-400">支持语音输入与语音播报。</span>
          ) : null}
        </div>
      </form>
    </section>
  );
}
