"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { getAccessToken, onSessionChange } from "@/lib/session";
import {
  getUnreadCount,
  listMyNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationStreamUrl,
} from "@/modules/notifications/api";
import { CUSTOMER_ORDER_CHANGED_EVENT, type NotificationItem } from "@/modules/notifications/types";

function timeAgo(iso: string): string {
  const diff = Math.max(0, Date.now() - new Date(iso).getTime());
  const min = Math.floor(diff / 60000);
  if (min < 1) return "Vừa xong";
  if (min < 60) return `${min} phút trước`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} giờ trước`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day} ngày trước`;
  return new Date(iso).toLocaleDateString("vi-VN");
}

function BellIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </svg>
  );
}

export function NotificationBell() {
  const [authed, setAuthed] = useState(false);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);

  const load = useCallback(async () => {
    if (!getAccessToken()) {
      setAuthed(false);
      setItems([]);
      setUnread(0);
      return;
    }
    setAuthed(true);
    try {
      const [list, count] = await Promise.all([
        listMyNotifications({ limit: 15 }),
        getUnreadCount(),
      ]);
      setItems(list);
      setUnread(count);
    } catch {
      // Best effort — a failed fetch just leaves the bell empty.
    }
  }, []);

  useEffect(() => {
    // Defer the initial load off the effect body so the first (possibly
    // synchronous) setState doesn't run inline — then keep in sync with login/logout.
    const timer = setTimeout(() => void load(), 0);
    const unsubscribe = onSessionChange(() => void load());
    return () => {
      clearTimeout(timer);
      unsubscribe();
    };
  }, [load]);

  // Real-time stream (SSE). Reconnects on every session change — this is what
  // picks up a freshly-refreshed access token (an already-open stream keeps
  // working past expiry; only a reconnect needs the new token) and closes the
  // stream on logout (notificationStreamUrl returns null when no token).
  useEffect(() => {
    if (typeof EventSource === "undefined") return;
    let es: EventSource | null = null;

    const handler = (evt: MessageEvent) => {
      try {
        const item = JSON.parse(evt.data) as NotificationItem;
        setItems((prev) => [item, ...prev.filter((n) => n.id !== item.id)].slice(0, 30));
        setUnread((c) => c + 1);
        if (item.orderId && (item.type.startsWith("ORDER_")
          || item.type.startsWith("CANCELLATION_")
          || item.type.startsWith("PAYMENT_")
          || item.type.startsWith("REFUND_"))) {
          window.dispatchEvent(new CustomEvent(CUSTOMER_ORDER_CHANGED_EVENT, { detail: { orderId: item.orderId } }));
        }
      } catch {
        // Ignore malformed payloads.
      }
    };

    const connect = () => {
      es?.close();
      const url = notificationStreamUrl();
      if (!url) return;
      es = new EventSource(url);
      es.addEventListener("notification", handler as EventListener);
    };

    connect();
    const unsubscribe = onSessionChange(connect);
    return () => {
      es?.close();
      unsubscribe();
    };
  }, []);

  // Close the dropdown on outside click.
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  const handleItemClick = async (item: NotificationItem) => {
    if (item.readAt) return;
    setItems((prev) =>
      prev.map((n) => (n.id === item.id ? { ...n, readAt: new Date().toISOString() } : n)),
    );
    setUnread((c) => Math.max(0, c - 1));
    try {
      await markNotificationRead(item.id);
    } catch {
      // Optimistic — keep the read state even if the call failed.
    }
  };

  const handleMarkAll = async () => {
    setItems((prev) => prev.map((n) => (n.readAt ? n : { ...n, readAt: new Date().toISOString() })));
    setUnread(0);
    try {
      await markAllNotificationsRead();
    } catch {
      // Best effort.
    }
  };

  if (!authed) return null;

  return (
    <div ref={containerRef} className="icon relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="relative flex h-10 w-5 items-center justify-center text-ivy-dark"
        aria-label="Thông báo"
        aria-expanded={open}
      >
        <BellIcon className="size-4.5" />
        {unread > 0 ? (
          <span className="absolute -right-1 top-0.5 flex size-4 items-center justify-center rounded-full bg-ivy-accent text-[10px] text-ivy-dark">
            {unread > 9 ? "9+" : unread}
          </span>
        ) : null}
      </button>

      {open ? (
        <div className="absolute right-0 top-full z-50 mt-1 w-80 border border-ivy-hairline bg-white shadow-[0_8px_24px_rgba(34,31,32,0.08)]">
          <div className="flex items-center justify-between border-b border-ivy-hairline px-4 py-3">
            <span className="text-[13px] font-medium uppercase tracking-[0.06em] text-ivy-dark">
              Thông báo
            </span>
            {unread > 0 ? (
              <button
                type="button"
                onClick={() => void handleMarkAll()}
                className="text-[12px] text-ivy-accent hover:underline"
              >
                Đánh dấu đã đọc
              </button>
            ) : null}
          </div>

          <div className="max-h-96 overflow-y-auto">
            {items.length === 0 ? (
              <p className="px-4 py-8 text-center text-[13px] text-ivy-text">
                Chưa có thông báo nào.
              </p>
            ) : (
              items.map((n) => {
                const inner = (
                  <div className={`flex gap-3 px-4 py-3 ${n.readAt ? "" : "bg-ivy-accent/5"}`}>
                    <span
                      className={`mt-1.5 size-2 shrink-0 rounded-full ${n.readAt ? "bg-transparent" : "bg-ivy-accent"}`}
                    />
                    <div className="min-w-0">
                      <p className="truncate text-[13px] font-medium text-ivy-dark">{n.subject}</p>
                      <p className="mt-0.5 line-clamp-2 text-[12px] text-ivy-text">{n.body}</p>
                      <p className="mt-1 text-[11px] text-ivy-text/60">{timeAgo(n.createdAt)}</p>
                    </div>
                  </div>
                );
                return n.orderId ? (
                  <Link
                    key={n.id}
                    href={`/tai-khoan/don-hang/${n.orderId}`}
                    onClick={() => void handleItemClick(n)}
                    className="block border-b border-ivy-hairline last:border-b-0 hover:bg-ivy-accent/3"
                  >
                    {inner}
                  </Link>
                ) : (
                  <button
                    key={n.id}
                    type="button"
                    onClick={() => void handleItemClick(n)}
                    className="block w-full border-b border-ivy-hairline text-left last:border-b-0 hover:bg-ivy-accent/3"
                  >
                    {inner}
                  </button>
                );
              })
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}
