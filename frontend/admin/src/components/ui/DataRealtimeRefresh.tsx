"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { ADMIN_LOCAL_MUTATION_KEY } from "@/modules/api/browser-client";

const REFRESH_INTERVAL_MS = 30_000;
const LOCAL_MUTATION_ECHO_WINDOW_MS = 3_000;

export function DataRealtimeRefresh() {
  const router = useRouter();

  useEffect(() => {
    const timer = window.setInterval(() => {
      const localMutationAt = Number(sessionStorage.getItem(ADMIN_LOCAL_MUTATION_KEY) ?? 0);
      if (Date.now() - localMutationAt < LOCAL_MUTATION_ECHO_WINDOW_MS) return;
      if (document.visibilityState === "visible") router.refresh();
    }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [router]);

  return null;
}
// Phase 11 intentionally replaces the retired application-wide SSE contract with bounded polling.
