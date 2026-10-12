import type { Metadata } from "next";
import { Bricolage_Grotesque, Plus_Jakarta_Sans } from "next/font/google";
import type { Viewport } from "next";
import { AdminShell } from "@/components/ui/AdminShell";
import { NavigationProgress } from "@/components/ui/NavigationProgress";
import { InventoryRealtimeRefresh } from "@/components/ui/InventoryRealtimeRefresh";
import { DataRealtimeRefresh } from "@/components/ui/DataRealtimeRefresh";
import "./globals.css";

const display = Bricolage_Grotesque({
  subsets: ["latin", "vietnamese"],
  variable: "--font-display",
  weight: ["700", "800"],
});

const body = Plus_Jakarta_Sans({
  subsets: ["latin", "vietnamese"],
  variable: "--font-body",
  weight: ["400", "500", "700", "800"],
});

export const metadata: Metadata = {
  title: "DHQ Admin",
  description: "Bảng quản trị vận hành DHQ",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body className={`${display.variable} ${body.variable}`}>
        <NavigationProgress />
        <InventoryRealtimeRefresh />
        <DataRealtimeRefresh />
        <AdminShell>{children}</AdminShell>
      </body>
    </html>
  );
}
