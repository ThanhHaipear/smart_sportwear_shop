import type { NavCategoryMenu } from "@/modules/content/types";

export const CATALOG_CATEGORY_NAV_ITEMS: NavCategoryMenu[] = [
  { label: "ÁO", href: "/danh-muc/ao-the-thao", variant: "plain" },
  { label: "QUẦN", href: "/danh-muc/quan-the-thao", variant: "plain" },
  { label: "ĐỒNG PHỤC", href: "/danh-muc/dong-phuc-the-thao", variant: "plain" },
  { label: "GIÀY", href: "/danh-muc/giay-the-thao", variant: "plain" },
  { label: "PHỤ KIỆN", href: "/danh-muc/phu-kien-the-thao", variant: "plain" },
];

/**
 * Old storefront routes represented sport as categories. Schema 1A models
 * sport as a facet, so old URLs redirect to one canonical leaf category plus
 * query filters instead of creating duplicate category rows.
 */
export const LEGACY_CATEGORY_REDIRECTS: Record<string, string> = {
  ao: "/danh-muc/ao-the-thao",
  quan: "/danh-muc/quan-the-thao",
  "ao-da-bong": "/danh-muc/ao-the-thao?sportType=FOOTBALL",
  "quan-da-bong": "/danh-muc/quan-the-thao?sportType=FOOTBALL",
  "ao-chay-bo": "/danh-muc/ao-the-thao?sportType=RUNNING",
  "quan-chay-bo": "/danh-muc/quan-the-thao?sportType=RUNNING",
  "giay-da-bong-fg": "/danh-muc/giay-the-thao?sportType=FOOTBALL",
  "giay-da-bong-tf": "/danh-muc/giay-the-thao?sportType=FOOTBALL",
  "giay-futsal": "/danh-muc/giay-the-thao?sportType=FOOTBALL",
  "giay-chay-bo": "/danh-muc/giay-the-thao?sportType=RUNNING",
  "phu-kien-da-bong": "/danh-muc/phu-kien-the-thao?sportType=FOOTBALL",
};

export function mergeCategoryRedirectQuery(
  target: string,
  current: Record<string, string | undefined>,
): string {
  const [pathname, targetQuery = ""] = target.split("?", 2);
  const merged = new URLSearchParams(targetQuery);
  Object.entries(current).forEach(([key, value]) => {
    if (value && !merged.has(key)) merged.set(key, value);
  });
  const query = merged.toString();
  return query ? `${pathname}?${query}` : pathname;
}
