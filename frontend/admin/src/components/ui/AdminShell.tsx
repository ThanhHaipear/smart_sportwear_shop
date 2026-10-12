"use client";

import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { AdminTopbar, Sidebar } from "@/components/ui/AdminChrome";

export function AdminShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isLoginPage = pathname === "/login";
  const isPrintPage = pathname.includes("/packing-slip");
  const [sidebarOpen, setSidebarOpen] = useState(false);

  useEffect(() => {
    setSidebarOpen(false);
  }, [pathname]);

  if (isLoginPage || isPrintPage) {
    return <>{children}</>;
  }

  return (
    <div className="admin-shell">
      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      {sidebarOpen ? <button className="sidebar-backdrop" aria-label="Đóng menu" onClick={() => setSidebarOpen(false)} /> : null}
      <div className="content-area">
        <AdminTopbar onOpenSidebar={() => setSidebarOpen(true)} />
        <div className="content">{children}</div>
      </div>
    </div>
  );
}
