// All shared content types live here. No imports from components/ allowed.

export type LifestyleSlug = "tin-chinh" | "kien-thuc" | "xu-huong" | "phong-cach" | "blog";

export interface CustomerServiceLink {
  label: string;
  href: string;
  external?: boolean;
}

export interface FooterLinkItem {
  label: string;
  href: string;
  external?: boolean;
}

export interface SocialLinkItem {
  label: string;
  href: string;
  src: string;
  width: number;
  height: number;
}

// Nav types (moved from components/layout/types.ts)
export interface NavSubItemGroup {
  heading: string;
  headingHref: string;
  links: { label: string; href: string }[];
}

export interface NavCategoryMenu {
  label: string;
  href: string;
  variant: "category" | "collection" | "about" | "plain";
  highlight?: boolean;
  quickLinks?: { label: string; href: string; highlight?: boolean }[];
  groups?: NavSubItemGroup[];
}

export interface FooterLinkColumn {
  title: string;
  links: { label: string; href: string }[];
}

export type HeaderNavItem = NavCategoryMenu;

// Marketing types (moved from components/marketing/types.ts)
export interface BannerSlide {
  href: string;
  image: string;
  alt: string;
}

export interface PromoCarouselItem {
  src: string | null;
  href: string;
  alt: string;
}

export interface PromoBannerItem {
  href: string;
  image: string;
}

export interface GalleryItem {
  href: string;
  image: string;
}

// Editorial types
export interface RelatedNewsItem {
  href: string;
  image: string;
  title: string;
  date: string;
}

export interface ArticleCategoryItem {
  label: string;
  href: string;
}

export interface LifestyleNavItem {
  label: string;
  slug: LifestyleSlug;
  href: string;
}

export interface MagazineItem {
  href: string;
  image: string;
  caption: string;
  title: string;
  date: string;
}

export interface ArticleTagItem {
  label: string;
  href: string;
}

export interface ArticleContentBlock {
  type: "text" | "image";
  text?: string;
  src?: string;
}
