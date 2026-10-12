import { CATALOG_CATEGORY_NAV_ITEMS } from "@/modules/category/catalogTaxonomy";
import type { Category } from "@/modules/category/types";
import { HEADER_NAV_ITEMS } from "@/modules/content/data/layout";
import type { NavCategoryMenu } from "@/modules/content/types";

const NON_CATEGORY_ITEMS = HEADER_NAV_ITEMS.filter(
  (item) => item.variant !== "category" && item.variant !== "plain",
);

/**
 * The header promotes sellable leaf categories. Gender, sport and brand stay
 * as facets, matching Schema 1A and preventing duplicate category trees such
 * as "ao-da-bong" and "ao-chay-bo".
 */
export function buildHeaderNavItems(categoryTree: Category[]): NavCategoryMenu[] {
  const leaves = [...categoryTree]
    .sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0))
    .flatMap((root) =>
      [...(root.children ?? [])].sort(
        (left, right) =>
          (left.sortOrder ?? 0) - (right.sortOrder ?? 0) ||
          left.name.localeCompare(right.name, "vi"),
      ),
    );

  if (leaves.length === 0) {
    return [...CATALOG_CATEGORY_NAV_ITEMS, ...NON_CATEGORY_ITEMS];
  }

  const categoryItems: NavCategoryMenu[] = leaves.map((category) => ({
    label: category.name.replace(/ thể thao$/iu, "").toLocaleUpperCase("vi"),
    href: `/danh-muc/${category.slug}`,
    variant: "plain",
  }));

  return [...categoryItems, ...NON_CATEGORY_ITEMS];
}
