import type { Metadata } from "next";
import type { Viewport } from "next";
import { AdminShell } from "@/components/ui/AdminShell";
import { NavigationProgress } from "@/components/ui/NavigationProgress";
import { InventoryRealtimeRefresh } from "@/components/ui/InventoryRealtimeRefresh";
import { DataRealtimeRefresh } from "@/components/ui/DataRealtimeRefresh";
import "./globals.css";

export const metadata: Metadata = {
  title: "DHQ Admin",
  description: "Bảng quản trị vận hành DHQ",
  icons: {
    icon: "/logo.png",
    shortcut: "/logo.png",
    apple: "/logo.png",
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body>
        <NavigationProgress />
        <InventoryRealtimeRefresh />
        <DataRealtimeRefresh />
        <AdminShell>{children}</AdminShell>
      </body>
    </html>
  );
}
