"use client";

import { useEffect, useRef, useState } from "react";
import { MessageCircle, X, Send, Bot } from "lucide-react";
import Link from "next/link";
import { getAccessToken } from "@/lib/session";
import { onSessionChange } from "@/lib/session";
import {
  clearChatConversation,
  getChatScope,
  getOrCreateChatSession,
  loadChatMessages,
  markChatSessionUsed,
  saveChatMessages,
  type StoredChatMessage,
  type StoredChatProduct,
} from "@/modules/chat/chatStorage";

const CHATBOT_BASE = (process.env.NEXT_PUBLIC_CHATBOT_API_BASE_URL ?? "http://localhost:8085").replace(/\/$/, "");
const WELCOME =
  "Xin chào 👋 Mình là trợ lý của Điểm Đến Thể Thao. Bạn cần tìm sản phẩm, hỏi size, giá hay chính sách đổi trả — cứ hỏi mình nhé!";

type ChatProduct = StoredChatProduct;
type Message = StoredChatMessage;

function formatPrice(p?: number | null): string {
  return typeof p === "number" && p > 0 ? `${p.toLocaleString("vi-VN")}đ` : "";
}

export function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<Message[]>([{ role: "bot", text: WELCOME }]);
  const [storageScope, setStorageScope] = useState("");
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const activeScopeRef = useRef("");

  useEffect(() => {
    const restore = () => {
      const scope = getChatScope();
      const stored = loadChatMessages(scope);
      activeScopeRef.current = scope;
      setStorageScope(scope);
      setMessages(stored.length ? stored : [{ role: "bot", text: WELCOME }]);
    };

    restore();
    return onSessionChange(() => {
      const previousScope = activeScopeRef.current;
      const nextScope = getChatScope();

      if (previousScope !== nextScope) {
        // Logging out ends the authenticated conversation. Logging in starts a
        // new one even when this account previously chatted in this browser.
        if (previousScope.startsWith("user:")) clearChatConversation(previousScope);
        if (nextScope.startsWith("user:")) clearChatConversation(nextScope);
      }

      restore();
    });
  }, []);

  useEffect(() => {
    if (!storageScope) return;
    const timer = window.setTimeout(() => saveChatMessages(storageScope, messages), 120);
    return () => window.clearTimeout(timer);
  }, [messages, storageScope]);

  useEffect(() => {
    if (open) {
      scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
      inputRef.current?.focus();
    }
  }, [messages, open, loading]);

  useEffect(() => {
    const openFromContact = (event: Event) => {
      const detail = (event as CustomEvent<{ message?: string }>).detail;
      setOpen(true);
      if (detail?.message) setInput(detail.message);
    };
    window.addEventListener("storefront:open-chat", openFromContact);
    return () => window.removeEventListener("storefront:open-chat", openFromContact);
  }, []);

  function appendToLastBot(delta: string) {
    setMessages((m) => {
      const next = [...m];
      const last = next[next.length - 1];
      if (last && last.role === "bot") next[next.length - 1] = { ...last, text: last.text + delta };
      return next;
    });
  }

  async function send() {
    const text = input.trim();
    if (!text || loading) return;
    setInput("");
    // add the user message + an empty bot bubble we stream into
    setMessages((m) => [...m, { role: "user", text }, { role: "bot", text: "" }]);
    setLoading(true);
    let gotAny = false;
    try {
      const token = getAccessToken();
      const scope = getChatScope();
      const session = getOrCreateChatSession(scope);
      markChatSessionUsed(scope); // only the first message of a new session carries isNewSession
      const res = await fetch(`${CHATBOT_BASE}/chat/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          sessionId: session.id,
          message: text,
          channel: "web",
          isNewSession: session.isNew,
          ...(token ? { accessToken: token } : {}),
        }),
      });
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      // Read the SSE stream: events are separated by a blank line, each line is "data: {json}".
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const events = buffer.split("\n\n");
        buffer = events.pop() ?? "";
        for (const evt of events) {
          const line = evt.split("\n").find((l) => l.startsWith("data:"));
          if (!line) continue;
          let payload: { delta?: string; done?: boolean; reply?: string; error?: string; products?: ChatProduct[] };
          try {
            payload = JSON.parse(line.slice(5).trim());
          } catch {
            continue;
          }
          if (payload.error) throw new Error(payload.error);
          if (payload.delta) {
            if (!gotAny) {
              gotAny = true;
              setLoading(false);
            }
            appendToLastBot(payload.delta);
          }
          if (payload.done) {
            const products = Array.isArray(payload.products) ? (payload.products as ChatProduct[]) : undefined;
            setMessages((m) => {
              const next = [...m];
              const last = next[next.length - 1];
              if (last && last.role === "bot") {
                next[next.length - 1] = {
                  ...last,
                  // The final reply has passed validate_answer; replace any
                  // streamed draft that was corrected downstream.
                  text: payload.reply || last.text,
                  ...(products?.length ? { products } : {}),
                };
              }
              return next;
            });
          }
        }
      }
    } catch {
      setMessages((m) => {
        const next = [...m];
        const last = next[next.length - 1];
        const msg = "Xin lỗi, hiện mình chưa kết nối được với trợ lý. Bạn vui lòng thử lại sau nhé.";
        if (last && last.role === "bot" && !last.text) next[next.length - 1] = { role: "bot", text: msg };
        else next.push({ role: "bot", text: msg });
        return next;
      });
    } finally {
      setLoading(false);
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  }

  return (
    <>
      {/* Panel */}
      {open && (
        <div className="fixed bottom-24 right-5 z-[120] flex h-[70vh] max-h-[560px] w-[calc(100vw-2.5rem)] max-w-[380px] flex-col overflow-hidden rounded-2xl border border-ivy-hairline bg-white shadow-2xl">
          {/* Header */}
          <div className="flex items-center gap-3 bg-ivy-dark px-4 py-3.5 text-white">
            <span className="flex size-9 items-center justify-center rounded-full bg-white/15">
              <Bot className="size-5" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[14px] font-semibold leading-tight">Trợ lý Điểm Đến Thể Thao</p>
              <p className="text-[11px] text-white/70">Thường trả lời trong vài giây</p>
            </div>
            <button
              type="button"
              onClick={() => setOpen(false)}
              aria-label="Đóng chat"
              className="rounded-full p-1.5 transition-colors hover:bg-white/15"
            >
              <X className="size-5" />
            </button>
          </div>

          {/* Messages */}
          <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto bg-[#fafafa] px-4 py-4">
            {messages.map((m, i) =>
              m.role === "bot" && m.text === "" ? (
                // Empty bot bubble = waiting for the first streamed token → typing dots.
                <div key={i} className="flex justify-start">
                  <div className="flex items-center gap-1.5 rounded-2xl rounded-bl-sm border border-ivy-hairline bg-white px-4 py-3">
                    <span className="size-2 animate-bounce rounded-full bg-ivy-text-muted [animation-delay:-0.3s]" />
                    <span className="size-2 animate-bounce rounded-full bg-ivy-text-muted [animation-delay:-0.15s]" />
                    <span className="size-2 animate-bounce rounded-full bg-ivy-text-muted" />
                  </div>
                </div>
              ) : (
                <div key={i} className={`flex flex-col ${m.role === "user" ? "items-end" : "items-start"}`}>
                  <div
                    className={`max-w-[80%] whitespace-pre-wrap rounded-2xl px-3.5 py-2.5 text-[13.5px] leading-relaxed ${
                      m.role === "user"
                        ? "rounded-br-sm bg-ivy-dark text-white"
                        : "rounded-bl-sm border border-ivy-hairline bg-white text-ivy-text"
                    }`}
                  >
                    {m.text}
                  </div>
                  {m.products && m.products.length > 0 && (
                    <div className="mt-2 flex w-full max-w-[90%] flex-col gap-2">
                      {m.products.map((p) => (
                        <Link
                          key={p.slug}
                          href={`/sanpham/${p.slug}`}
                          className="flex items-center gap-2.5 rounded-xl border border-ivy-hairline bg-white p-2 transition-colors hover:border-ivy-dark"
                        >
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={p.image || "/images/logo-v2.png"}
                            alt={p.name}
                            loading="lazy"
                            className="size-14 shrink-0 rounded-lg bg-[#f3f3f3] object-cover"
                          />
                          <div className="min-w-0 flex-1">
                            <p className="line-clamp-2 text-[12.5px] font-medium leading-snug text-ivy-dark">{p.name}</p>
                            {formatPrice(p.price) && (
                              <p className="mt-1 text-[12.5px] font-semibold text-ivy-dark">{formatPrice(p.price)}</p>
                            )}
                          </div>
                        </Link>
                      ))}
                    </div>
                  )}
                </div>
              ),
            )}
          </div>

          {/* Input */}
          <div className="flex items-end gap-2 border-t border-ivy-hairline bg-white px-3 py-3">
            <textarea
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              rows={1}
              placeholder="Nhập tin nhắn..."
              className="max-h-28 min-h-[42px] flex-1 resize-none rounded-xl border border-ivy-hairline px-3.5 py-2.5 text-[13.5px] text-ivy-dark outline-none focus:border-ivy-dark"
            />
            <button
              type="button"
              onClick={send}
              disabled={loading || !input.trim()}
              aria-label="Gửi"
              className="flex size-[42px] shrink-0 items-center justify-center rounded-xl bg-ivy-dark text-white transition-opacity hover:opacity-90 disabled:opacity-40"
            >
              <Send className="size-[18px]" />
            </button>
          </div>
        </div>
      )}

      {/* Floating button */}
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={open ? "Đóng trợ lý" : "Mở trợ lý chat"}
        className="fixed bottom-[max(1.25rem,env(safe-area-inset-bottom))] right-3 z-[120] flex size-12 items-center justify-center rounded-full bg-ivy-dark text-white shadow-xl transition-transform hover:scale-105 md:right-5 md:size-14"
      >
        {open ? <X className="size-6" /> : <MessageCircle className="size-6" />}
      </button>
    </>
  );
}
