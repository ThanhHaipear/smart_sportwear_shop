import { CATALOG_CATEGORY_NAV_ITEMS } from "@/modules/category/catalogTaxonomy";
import type {
  CustomerServiceLink,
  FooterLinkItem,
  HeaderNavItem,
} from "@/modules/content/types";

export const CUSTOMER_SERVICE_LINKS: CustomerServiceLink[] = [
  { label: "Hotline", href: "tel:02438341002" },
  { label: "Liên hệ", href: "/lien-he" },
  { label: "Hệ thống cửa hàng", href: "/cua-hang" },
  { label: "Tra cứu đơn hàng", href: "/tra-cuu-don-hang" },
];

/** Static fallback when the category API is unavailable. */
export const HEADER_NAV_ITEMS: HeaderNavItem[] = [
  ...CATALOG_CATEGORY_NAV_ITEMS,
  {
    label: "BỘ SƯU TẬP",
    href: "/bo-suu-tap",
    variant: "collection",
  },
  {
    label: "VỀ CHÚNG TÔI",
    href: "/about/gioi-thieu",
    variant: "about",
    groups: [
      {
        heading: "Điểm Đến Thể Thao",
        headingHref: "/about/gioi-thieu",
        links: [
          { label: "Giới thiệu cửa hàng", href: "/about/gioi-thieu" },
          { label: "Hệ thống cửa hàng", href: "/lien-he" },
          { label: "Tin tức thể thao", href: "/tin-tuc/tin-chinh" },
          { label: "Liên hệ", href: "/lien-he" },
        ],
      },
    ],
  },
];

export const FOOTER_INTRO_LINKS: FooterLinkItem[] = [
  { label: "Về Điểm Đến Thể Thao", href: "/about/gioi-thieu" },
  { label: "Hệ thống cửa hàng", href: "/cua-hang" },
  { label: "Tin tức thể thao", href: "/tin-tuc/tin-chinh" },
];

export const FOOTER_CUSTOMER_SERVICE_LINKS: FooterLinkItem[] = [
  { label: "Chính sách điều khoản", href: "/about/gioi-thieu" },
  { label: "Hướng dẫn mua hàng", href: "/about/gioi-thieu" },
  { label: "Chính sách thanh toán", href: "/about/gioi-thieu" },
  { label: "Chính sách đổi trả", href: "/about/gioi-thieu" },
  { label: "Chính sách bảo hành", href: "/about/gioi-thieu" },
  { label: "Chính sách thẻ thành viên", href: "/about/gioi-thieu" },
  { label: "Q&A", href: "/lien-he" },
];

export const FOOTER_CONTACT_LINKS: FooterLinkItem[] = [
  { label: "Hotline: 0243 834 1002", href: "tel:02438341002" },
  { label: "Tra cứu đơn hàng", href: "/tra-cuu-don-hang" },
  { label: "Hệ thống cửa hàng", href: "/cua-hang" },
  { label: "Liên hệ", href: "/lien-he" },
];
