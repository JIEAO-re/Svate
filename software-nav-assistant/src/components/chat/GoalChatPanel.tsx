"use client";

import React, { ChangeEvent, ClipboardEvent, DragEvent, FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Mic, MicOff, MessageCircle, Send, Sparkles, Paperclip, FileText, X } from "lucide-react";
import { compressImageToBase64 } from "@/lib/utils/image-helpers";

type ChatRole = "user" | "assistant";

interface BubbleAttachment {
  kind: "image" | "file";
  name: string;
  previewUrl?: string;
}

interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  attachments?: BubbleAttachment[];
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

// One staged upload. Images carry compressed base64 (sent as vision input);
// text-like files carry extracted UTF-8 text; other binaries carry only metadata.
interface Attachment {
  id: string;
  kind: "image" | "file";
  name: string;
  mimeType: string;
  sizeBytes: number;
  previewUrl?: string;
  dataBase64?: string;
  textContent?: string;
}

const MAX_ATTACHMENTS = 4;
const MAX_FILE_BYTES = 5 * 1024 * 1024;

const TEXT_EXTENSIONS = new Set([
  "txt", "md", "markdown", "json", "csv", "log", "xml", "yml", "yaml",
  "html", "htm", "css", "js", "jsx", "ts", "tsx", "py", "java", "kt", "kts",
  "c", "cpp", "h", "go", "rs", "rb", "php", "sh", "sql", "toml", "ini", "conf",
]);

function isTextLike(file: File): boolean {
  if (file.type.startsWith("text/")) return true;
  if (/(json|xml|csv|yaml|javascript|typescript)/.test(file.type)) return true;
  const ext = file.name.split(".").pop()?.toLowerCase() ?? "";
  return TEXT_EXTENSIONS.has(ext);
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
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
      content: "您好，我是导航助手。请告诉我您想打开哪个应用、要完成什么操作，例如“打开微信给小明打视频”。也可以上传图片或文件作为参考。",
    },
  ]);
  const [inputValue, setInputValue] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [isSpeechSupported, setIsSpeechSupported] = useState(false);
  const [readyPlan, setReadyPlan] = useState<GoalPlan | null>(null);
  const [errorText, setErrorText] = useState<string | null>(null);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [isDragging, setIsDragging] = useState(false);

  const listRef = useRef<HTMLDivElement | null>(null);
  const recognitionRef = useRef<BrowserSpeechRecognition | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!listRef.current) return;
    listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [messages, readyPlan, attachments]);

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

  // Read selected files: images get compressed to base64; text-like files get
  // their text extracted; other binaries keep only metadata.
  const addFiles = useCallback(async (fileList: FileList | File[]) => {
    const files = Array.from(fileList);
    if (files.length === 0) return;
    setErrorText(null);

    const accepted: Attachment[] = [];
    for (const file of files) {
      if (file.size > MAX_FILE_BYTES) {
        setErrorText(`「${file.name}」超过 ${Math.round(MAX_FILE_BYTES / 1024 / 1024)}MB，已跳过`);
        continue;
      }
      try {
        if (file.type.startsWith("image/")) {
          const dataUrl = await compressImageToBase64(file, 1024);
          const base64 = dataUrl.split(",")[1] ?? "";
          accepted.push({
            id: createId(),
            kind: "image",
            name: file.name,
            mimeType: "image/jpeg",
            sizeBytes: file.size,
            previewUrl: dataUrl,
            dataBase64: base64,
          });
        } else if (isTextLike(file)) {
          const text = await file.text();
          accepted.push({
            id: createId(),
            kind: "file",
            name: file.name,
            mimeType: file.type || "text/plain",
            sizeBytes: file.size,
            textContent: text.slice(0, 12000),
          });
        } else {
          accepted.push({
            id: createId(),
            kind: "file",
            name: file.name,
            mimeType: file.type || "application/octet-stream",
            sizeBytes: file.size,
          });
        }
      } catch {
        setErrorText(`「${file.name}」读取失败，已跳过`);
      }
    }

    if (accepted.length > 0) {
      setAttachments((prev) => {
        const merged = [...prev, ...accepted];
        if (merged.length > MAX_ATTACHMENTS) setErrorText(`最多上传 ${MAX_ATTACHMENTS} 个附件`);
        return merged.slice(0, MAX_ATTACHMENTS);
      });
    }
  }, []);

  const removeAttachment = useCallback((id: string) => {
    setAttachments((prev) => prev.filter((a) => a.id !== id));
  }, []);

  const handleFileInputChange = useCallback((event: ChangeEvent<HTMLInputElement>) => {
    if (event.target.files) void addFiles(event.target.files);
    event.target.value = "";
  }, [addFiles]);

  const handlePaste = useCallback((event: ClipboardEvent<HTMLInputElement>) => {
    const files = event.clipboardData?.files;
    if (files && files.length > 0) void addFiles(files);
  }, [addFiles]);

  const handleDrop = useCallback((event: DragEvent<HTMLElement>) => {
    event.preventDefault();
    setIsDragging(false);
    if (event.dataTransfer?.files) void addFiles(event.dataTransfer.files);
  }, [addFiles]);

  const sendMessage = useCallback(async (content: string) => {
    const trimmed = content.trim();
    const currentAttachments = attachments;
    if ((!trimmed && currentAttachments.length === 0) || isSending) return;

    setErrorText(null);
    setReadyPlan(null);

    const synthesizedContent = trimmed
      ? trimmed
      : (currentAttachments.every((a) => a.kind === "image")
          ? "[图片]"
          : `[附件] ${currentAttachments.map((a) => a.name).join("、")}`
        ).slice(0, 200);

    const userMessage: ChatMessage = {
      id: createId(),
      role: "user",
      content: synthesizedContent,
      attachments: currentAttachments.length
        ? currentAttachments.map((a) => ({ kind: a.kind, name: a.name, previewUrl: a.previewUrl }))
        : undefined,
    };
    const nextMessages = [...messages, userMessage];
    setMessages(nextMessages);
    setInputValue("");
    setAttachments([]);
    setIsSending(true);

    const wireAttachments = currentAttachments.map((a) => ({
      kind: a.kind,
      name: a.name,
      mime_type: a.mimeType,
      size_bytes: a.sizeBytes,
      ...(a.dataBase64 ? { data_base64: a.dataBase64 } : {}),
      ...(a.textContent ? { text_content: a.textContent } : {}),
    }));

    try {
      const response = await fetch("/api/chat-goal", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          messages: nextMessages.map((msg) => ({ role: msg.role, content: msg.content })),
          attachments: wireAttachments,
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
  }, [attachments, isSending, messages, speakAssistantReply]);

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

  const canSend = inputValue.trim().length > 0 || attachments.length > 0;

  return (
    <section
      className="relative w-full rounded-3xl bg-white border border-gray-200 shadow-sm overflow-hidden text-gray-900"
      onDragOver={(event) => { event.preventDefault(); setIsDragging(true); }}
      onDragLeave={(event) => { event.preventDefault(); setIsDragging(false); }}
      onDrop={handleDrop}
    >
      {isDragging && (
        <div className="absolute inset-0 z-20 rounded-3xl bg-white/85 backdrop-blur-sm border-2 border-dashed border-gray-400 flex items-center justify-center pointer-events-none">
          <p className="text-gray-700 font-bold flex items-center gap-2">
            <Paperclip className="w-5 h-5" /> 松开以上传图片或文件
          </p>
        </div>
      )}

      <div className="border-b border-gray-200 px-5 py-4">
        <h2 className="text-lg font-black text-gray-900 flex items-center gap-2">
          <MessageCircle className="w-5 h-5 text-gray-900" />
          先聊目标，再开始引导
        </h2>
        <p className="text-sm text-gray-500 mt-1">告诉我应用和任务，或上传图片/文件，确认后会出现“开始引导”模块。</p>
      </div>

      <div ref={listRef} className="h-[380px] overflow-y-auto px-4 py-4 space-y-3 custom-scrollbar">
        {messages.map((message) => (
          <div
            key={message.id}
            className={`max-w-[88%] rounded-3xl px-4 py-3 text-sm leading-relaxed ${
              message.role === "assistant"
                ? "bg-gray-50 text-gray-800 border border-gray-100"
                : "ml-auto bg-gray-100 text-gray-900"
            }`}
          >
            {message.attachments && message.attachments.length > 0 && (
              <div className="flex flex-wrap gap-2 mb-2">
                {message.attachments.map((att, index) =>
                  att.kind === "image" && att.previewUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      key={index}
                      src={att.previewUrl}
                      alt={att.name}
                      className="h-20 w-20 object-cover rounded-lg border border-black/10"
                    />
                  ) : (
                    <span key={index} className="inline-flex items-center gap-1 text-xs bg-black/5 rounded-md px-2 py-1">
                      <FileText className="w-3 h-3" />
                      <span className="max-w-[160px] truncate">{att.name}</span>
                    </span>
                  ),
                )}
              </div>
            )}
            {message.content}
          </div>
        ))}

        {readyPlan && (
          <div className="rounded-2xl border border-gray-200 bg-gray-50 p-4">
            <div className="flex items-center gap-2 text-gray-900 font-black mb-2">
              <Sparkles className="w-4 h-4" />
              开始引导
            </div>
            <p className="text-sm text-gray-600">目标应用：{readyPlan.targetAppName}</p>
            <p className="text-sm text-gray-600 mt-1">任务：{readyPlan.inferredGoal}</p>
            <button
              onClick={() => onStartGuide(readyPlan)}
              className="mt-3 w-full rounded-full bg-black text-white py-2.5 font-bold hover:bg-gray-800 transition-colors"
            >
              开始引导并关闭对话
            </button>
          </div>
        )}
      </div>

      <form onSubmit={handleSubmit} className="border-t border-gray-200 p-4">
        {/* Staged attachment previews */}
        {attachments.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-3">
            {attachments.map((a) => (
              <div key={a.id} className="relative">
                {a.kind === "image" && a.previewUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={a.previewUrl}
                    alt={a.name}
                    className="h-16 w-16 object-cover rounded-xl border border-gray-200"
                  />
                ) : (
                  <div className="flex items-center gap-2 h-16 px-3 rounded-xl border border-gray-200 bg-gray-50 max-w-[200px]">
                    <FileText className="w-5 h-5 text-gray-500 shrink-0" />
                    <div className="min-w-0">
                      <p className="text-xs font-medium text-gray-800 truncate">{a.name}</p>
                      <p className="text-[10px] text-gray-400">{formatBytes(a.sizeBytes)}</p>
                    </div>
                  </div>
                )}
                <button
                  type="button"
                  onClick={() => removeAttachment(a.id)}
                  className="absolute -top-1.5 -right-1.5 h-5 w-5 rounded-full bg-gray-900 text-white flex items-center justify-center shadow"
                  title="移除"
                >
                  <X className="w-3 h-3" />
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="flex items-center gap-2">
          {/* Attach files / images */}
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={isSending}
            className="h-11 w-11 shrink-0 rounded-full flex items-center justify-center bg-gray-100 text-gray-500 hover:bg-gray-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            title="上传图片或文件"
          >
            <Paperclip className="w-5 h-5" />
          </button>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept="image/*,.pdf,.txt,.md,.json,.csv,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
            onChange={handleFileInputChange}
            className="hidden"
          />

          <button
            type="button"
            onClick={toggleVoiceInput}
            disabled={!isSpeechSupported || isSending}
            className={`h-11 w-11 shrink-0 rounded-full flex items-center justify-center transition-colors ${
              isListening
                ? "bg-black text-white"
                : "bg-gray-100 text-gray-500 hover:bg-gray-200"
            } ${!isSpeechSupported || isSending ? "opacity-50 cursor-not-allowed" : ""}`}
            title={isSpeechSupported ? "语音输入" : "当前浏览器不支持语音输入"}
          >
            {isListening ? <MicOff className="w-5 h-5" /> : <Mic className="w-5 h-5" />}
          </button>
          <input
            value={inputValue}
            onChange={(event) => setInputValue(event.target.value)}
            onPaste={handlePaste}
            placeholder="例如：打开微信，给小明发视频通话"
            className="flex-1 h-11 rounded-full bg-white border border-gray-200 px-4 text-sm outline-none text-gray-900 placeholder:text-gray-400 focus:ring-2 focus:ring-black/10 focus:border-gray-300"
            disabled={isSending}
          />
          <button
            type="submit"
            disabled={isSending || !canSend}
            className="h-11 w-11 shrink-0 rounded-full bg-black text-white hover:bg-gray-800 disabled:bg-gray-200 disabled:text-gray-400 disabled:cursor-not-allowed flex items-center justify-center"
            title="发送"
          >
            <Send className="w-4 h-4" />
          </button>
        </div>

        <div className="mt-2 min-h-5 text-xs">
          {errorText ? <span className="text-gray-700">对话失败：{errorText}</span> : null}
          {!errorText && isListening ? <span className="text-gray-500">正在听您说话...</span> : null}
          {!errorText && !isListening && !isSpeechSupported ? (
            <span className="text-gray-400">支持上传图片/文件，可拖拽或粘贴。当前浏览器不支持语音输入，可直接打字。</span>
          ) : null}
          {!errorText && !isListening && isSpeechSupported && chatPayloadMessages.length > 0 ? (
            <span className="text-gray-400">支持语音输入、图片/文件上传（可拖拽或粘贴）。</span>
          ) : null}
        </div>
      </form>
    </section>
  );
}
