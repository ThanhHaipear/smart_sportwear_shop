import { SearchResultsPage } from "@/modules/category/pages/SearchResultsPage";

import type { Metadata } from "next";

type SearchParams = { q?: string; page?: string; discount?: string };

export async function generateMetadata({ searchParams }: { searchParams: Promise<SearchParams> }): Promise<Metadata> {
  const { q } = await searchParams;
  return {
    title: q ? `Tìm kiếm “${q}” | Điểm Đến Thể Thao` : "Tìm kiếm sản phẩm | Điểm Đến Thể Thao",
  };
}

export default async function SearchPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  return <SearchResultsPage searchParams={searchParams} />;
}
