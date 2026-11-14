import { LoginRouteView } from "@/modules/auth/pages/LoginRouteView";

import type { Metadata } from "next";

export const metadata: Metadata = { title: "Đăng nhập | Điểm Đến Thể Thao" };

export default function LoginPage() {
  return <LoginRouteView />;
}
