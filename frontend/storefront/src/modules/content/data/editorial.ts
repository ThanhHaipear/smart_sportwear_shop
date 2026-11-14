import type {
  ArticleCategoryItem,
  ArticleContentBlock,
  ArticleTagItem,
  LifestyleNavItem,
  MagazineItem,
  PromoBannerItem,
  RelatedNewsItem,
} from "@/modules/content/types";

export const ARTICLE_CATEGORIES: ArticleCategoryItem[] = [
  { label: "Áo thể thao", href: "/danh-muc/ao-the-thao" },
  { label: "Đồng phục thể thao", href: "/danh-muc/dong-phuc-the-thao" },
  { label: "Giày thể thao", href: "/danh-muc/giay-the-thao" },
];

export const LIFESTYLE_NAV_ITEMS: LifestyleNavItem[] = [
  { label: "TIN TỨC", slug: "tin-chinh", href: "/tin-tuc/tin-chinh" },
];

export const MAGAZINE_ITEMS: MagazineItem[] = [
  {
    href: "/danh-muc/ao-the-thao",
    image: "/images/slideshow_9.webp",
    caption: "Những lưu ý khi lựa chọn áo tập và áo đấu phù hợp với cường độ vận động.",
    title: "CHỌN ÁO THỂ THAO THOÁNG KHÍ VÀ VỪA VẶN",
    date: "Cẩm nang sản phẩm",
  },
  {
    href: "/danh-muc/giay-the-thao",
    image: "/images/slideshow_5.webp",
    caption: "Độ ôm, đệm và độ bám là ba yếu tố quan trọng khi chọn giày thể thao.",
    title: "CÁCH CHỌN GIÀY PHÙ HỢP VỚI MÔN THỂ THAO",
    date: "Cẩm nang sản phẩm",
  },
  {
    href: "/danh-muc/dong-phuc-the-thao",
    image: "/images/slideshow_2.webp",
    caption: "Gợi ý phối màu, logo và chất liệu cho trang phục thi đấu đồng đội.",
    title: "ĐỒNG PHỤC THỂ THAO: ĐỒNG BỘ VÀ THOẢI MÁI",
    date: "Cẩm nang sản phẩm",
  },
];

export const RELATED_NEWS_ITEMS: RelatedNewsItem[] = [
  {
    href: "/danh-muc/ao-the-thao",
    image: "/images/slideshow_9.webp",
    title: "Lựa chọn áo thể thao theo nhu cầu vận động",
    date: "Cẩm nang sản phẩm",
  },
  {
    href: "/danh-muc/giay-the-thao",
    image: "/images/slideshow_5.webp",
    title: "Tìm đôi giày phù hợp với môn thể thao của bạn",
    date: "Cẩm nang sản phẩm",
  },
];

export const ARTICLE_PROMO_BANNER: PromoBannerItem = {
  href: "/danh-muc/ao-the-thao",
  image: "/images/banner-promo-1.png",
};

export const ARTICLE_DETAIL_BLOCKS: ArticleContentBlock[] = [
  {
    type: "text",
    text: "Trang phục thể thao phù hợp giúp cơ thể thoải mái và hạn chế cản trở chuyển động trong suốt quá trình tập luyện.",
  },
  { type: "image", src: "/images/banner-hero-1.png" },
  {
    type: "text",
    text: "Hãy ưu tiên chất liệu thoáng khí, thấm hút tốt và có độ co giãn phù hợp với môn thể thao bạn lựa chọn.",
  },
  {
    type: "text",
    text: "Với giày thể thao, cần kiểm tra độ ôm ở gót, khoảng trống đầu ngón chân và độ bám của đế trước khi quyết định.",
  },
  {
    type: "text",
    text: "Nếu chưa chắc chắn về kích thước, bạn có thể liên hệ đội ngũ tư vấn hoặc thử sản phẩm trực tiếp tại cửa hàng.",
  },
];

export const ARTICLE_DETAIL_TAGS: ArticleTagItem[] = [
  { label: "Áo thể thao", href: "/danh-muc/ao-the-thao" },
  { label: "Giày thể thao", href: "/danh-muc/giay-the-thao" },
  { label: "Đồng phục", href: "/danh-muc/dong-phuc-the-thao" },
];
