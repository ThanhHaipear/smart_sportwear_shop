"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { ADMIN_LOCAL_MUTATION_KEY } from "@/modules/api/browser-client";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "http://localhost:8082";
const REFRESH_DEBOUNCE_MS = 350;
const LOCAL_MUTATION_ECHO_WINDOW_MS = 3_000;

export function DataRealtimeRefresh() {
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (typeof EventSource === "undefined") return;
    const stream = new EventSource(`${API_BASE}/api/v1/realtime/stream`);
    let timer: number | undefined;
    const refresh = (event: MessageEvent) => {
      let scope = "application";
      try { scope = (JSON.parse(event.data) as { scope?: string }).scope ?? scope; } catch { /* ignore malformed event */ }
      if (!isRelevantToAdmin(scope, pathname)) return;
      const localMutationAt = Number(sessionStorage.getItem(ADMIN_LOCAL_MUTATION_KEY) ?? 0);
      if (Date.now() - localMutationAt < LOCAL_MUTATION_ECHO_WINDOW_MS) return;
      window.clearTimeout(timer);
      timer = window.setTimeout(() => {
        if (document.visibilityState === "visible") {
          router.refresh();
        }
      }, REFRESH_DEBOUNCE_MS);
    };
    stream.addEventListener("data-changed", refresh as EventListener);
    return () => {
      window.clearTimeout(timer);
      stream.close();
    };
  }, [pathname, router]);

  return null;
}

function isRelevantToAdmin(scope: string, pathname: string) {
  if (pathname === "/") return true;
  const routes: Record<string, string[]> = {
    products: ["/products"], variants: ["/products"], categories: ["/categories", "/products"],
    brands: ["/brands", "/products"], orders: ["/orders"],
    returns: ["/returns", "/orders"], users: ["/users-roles", "/customers"],
    settings: ["/system-config"],
  };
  return (routes[scope] ?? []).some((route) => pathname.startsWith(route));
}
