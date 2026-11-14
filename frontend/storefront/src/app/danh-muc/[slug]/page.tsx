import {
  LEGACY_CATEGORY_REDIRECTS,
  mergeCategoryRedirectQuery,
} from "@/modules/category/catalogTaxonomy";
import { CategoryListingPage } from "@/modules/category/pages/CategoryListingPage";
import type { Metadata } from "next";

function categoryTitle(slug: string) {
  return slug.split("-").map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(" ");
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const title = categoryTitle(slug);
  return {
    title: `${title} | Điểm Đến Thể Thao`,
    description: `Khám phá ${title.toLowerCase()} chính hãng tại Điểm Đến Thể Thao.`,
  };
}

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
