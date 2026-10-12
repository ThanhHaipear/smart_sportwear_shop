import { CATALOG_CATEGORY_NAV_ITEMS } from "@/modules/category/catalogTaxonomy";
import type {
  CustomerServiceLink,
  FooterLinkItem,
  HeaderNavItem,
  SocialLinkItem,
} from "@/modules/content/types";

export const CUSTOMER_SERVICE_LINKS: CustomerServiceLink[] = [
  { label: "Hotline", href: "tel:02466623434" },
  { label: "Live Chat", href: "#" },
  { label: "Messenger", href: "http://messenger.com/t/thoitrangivymoda", external: true },
  { label: "Email", href: "mailto:saleadmin@ivy.com.vn" },
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
  { label: "Tuyển dụng", href: "https://tuyendung.ivy.com.vn", external: true },
  { label: "Hệ thống cửa hàng", href: "/cua-hang" },
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
  { label: "Hotline", href: "tel:02466623434" },
  { label: "Email", href: "mailto:saleadmin@ivy.com.vn" },
  { label: "Live Chat", href: "#" },
  { label: "Messenger", href: "http://messenger.com/t/thoitrangivymoda", external: true },
  { label: "Liên hệ", href: "/lien-he" },
];

export const FOOTER_SOCIAL_LINKS: SocialLinkItem[] = [
  {
    label: "Facebook",
    href: "https://www.facebook.com/thoitrangivymoda/",
    src: "/images/ivymoda/common/ic_fb.svg",
    width: 12,
    height: 24,
  },
  {
    label: "Google",
    href: "/",
    src: "/images/ivymoda/common/ic_gg.svg",
    width: 22,
    height: 22,
  },
  {
    label: "Instagram",
    href: "https://www.instagram.com/ivy_moda/",
    src: "/images/ivymoda/common/ic_instagram.svg",
    width: 28,
    height: 28,
  },
];
