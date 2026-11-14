import { Headphones, MapPin, MessageCircle, ShoppingBag } from "lucide-react";
import type { LucideIcon } from "lucide-react";

export interface ContactInfoCardData {
  icon: LucideIcon;
  label: string;
  lines: string[];
}

export const CONTACT_INFO_CARDS: ContactInfoCardData[] = [
  {
    icon: MapPin,
    label: "Địa chỉ",
    lines: ["267 Đ. Quang Trung, P. Quang Trung, Hà Đông, Hà Nội"],
  },
  {
    icon: MessageCircle,
    label: "Hỗ trợ trực tuyến",
    lines: ["Gửi yêu cầu bằng biểu mẫu hoặc trò chuyện trực tiếp trên website"],
  },
  {
    icon: ShoppingBag,
    label: "Mua hàng online",
    lines: ["0243 834 1002"],
  },
  {
    icon: Headphones,
    label: "Chăm sóc khách hàng",
    lines: [
      "Hotline: 0243 834 1002",
      "Thứ Hai đến Thứ Bảy, từ 8:00 đến 17:30",
    ],
  },
];
