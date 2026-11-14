import { CartRouteView } from "@/modules/cart/pages/CartRouteView";

import type { Metadata } from "next";

export const metadata: Metadata = { title: "Giỏ hàng | Điểm Đến Thể Thao" };

export default function CartPage() {
  return <CartRouteView />;
}
