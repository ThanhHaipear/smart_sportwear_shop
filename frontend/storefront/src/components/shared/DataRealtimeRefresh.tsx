"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
const REFRESH_INTERVAL_MS = 30_000;

export function DataRealtimeRefresh() {
  const router = useRouter();

  useEffect(() => {
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") router.refresh(); }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [router]);

  return null;
}
// Customer-specific notifications continue to use SSE; general catalog data uses bounded polling.
