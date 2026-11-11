"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";

const INVENTORY_PATH = "/inventory";
const REFRESH_INTERVAL_MS = 30_000;

export function InventoryRealtimeRefresh() {
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (pathname !== INVENTORY_PATH) return;
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") router.refresh(); }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [pathname, router]);

  return null;
}
