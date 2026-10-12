import {
  LEGACY_CATEGORY_REDIRECTS,
  mergeCategoryRedirectQuery,
} from "@/modules/category/catalogTaxonomy";
import { CategoryListingPage } from "@/modules/category/pages/CategoryListingPage";

type CategorySearchParams = {
  page?: string;
  sort?: string;
  sortBy?: string;
  sortOrder?: string;
  size?: string;
  color?: string;
  minPrice?: string;
  maxPrice?: string;
  discount?: string;
  gender?: string;
  sportType?: string;
  brandSlug?: string;
};

export default async function CategoryPage({
  params,
  searchParams,
}: {
  params: Promise<{ slug: string }>;
  searchParams: Promise<CategorySearchParams>;
}) {
  const { slug } = await params;
  const resolvedSearchParams = await searchParams;
  const canonicalTarget = LEGACY_CATEGORY_REDIRECTS[slug];

  if (canonicalTarget) {
    // next.config performs the HTTP redirect after the dev server restarts.
    // This fallback also renders the correct catalog immediately during HMR.
    const canonicalUrl = new URL(
      mergeCategoryRedirectQuery(canonicalTarget, resolvedSearchParams),
      "http://storefront.local",
    );
    const canonicalSlug = canonicalUrl.pathname.split("/").filter(Boolean).at(-1) ?? slug;
    const canonicalSearchParams = Object.fromEntries(canonicalUrl.searchParams.entries());
    return (
      <CategoryListingPage
        params={Promise.resolve({ slug: canonicalSlug })}
        searchParams={Promise.resolve(canonicalSearchParams)}
      />
    );
  }

  return <CategoryListingPage params={params} searchParams={searchParams} />;
}
