import { Pagination } from "@/components/shared/Pagination";
import { CategorySidebarFilter } from "@/modules/category/components/CategorySidebarFilter";
import { CategoryToolbar } from "@/modules/category/components/CategoryToolbar";
import { fetchSearchResults } from "@/modules/category/queries";
import type { PageMeta } from "@/modules/category/types";
import { ProductCard } from "@/modules/product/components/ProductCard";
import { mapProductListItem } from "@/modules/product/mappers";
import type { ProductListItem } from "@/modules/product/types";
import { SearchFilterChips } from "@/modules/category/components/SearchFilterChips";

const PAGE_SIZE = 20;

export async function SearchResultsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | undefined>>;
}) {
  const params = await searchParams;
  const { q, page: pageParam, discount, sort, size, color, minPrice, maxPrice, gender, brandSlug, categorySlug, sportType } = params;
  const query = q?.trim() ?? "";
  const currentPage = Math.max(1, Number(pageParam ?? 1));
  const isSaleListing = discount === "any";
  const filters = { discount, sort, size, color, minPrice, maxPrice, gender, brandSlug, categorySlug, sportType };
  const { products, meta, error }: { products: ProductListItem[]; meta: PageMeta; error: boolean } = await fetchSearchResults(
    query,
    currentPage,
    PAGE_SIZE,
    filters,
  );

  const mappedProducts = products.map(mapProductListItem);
  const title = isSaleListing
    ? "Tất cả sản phẩm ưu đãi"
    : query
      ? `Kết quả tìm kiếm theo "${query}"`
      : "Kết quả tìm kiếm";

  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
      <div className="mx-auto flex max-w-[1380px] flex-col gap-8 px-4 pb-16 pt-6 lg:flex-row">
        <CategorySidebarFilter
          initialSize={size}
          initialColor={color}
          initialMinPrice={minPrice ? Number(minPrice) : undefined}
          initialMaxPrice={maxPrice ? Number(maxPrice) : undefined}
          initialDiscount={discount}
          initialGender={gender}
          initialSportType={sportType}
          initialBrandSlug={brandSlug}
        />
        <div className="flex-1">
          <CategoryToolbar title={title} resultCount={meta.total} currentSort={sort} />
          {meta.searchMode === "KEYWORD_FALLBACK" ? (
            <p className="mb-4 rounded-md bg-amber-50 px-4 py-3 text-sm text-amber-800">
              Đang hiển thị kết quả từ khóa vì tìm kiếm nâng cao tạm thời chưa khả dụng.
            </p>
          ) : null}
          <SearchFilterChips
            params={{ q: query || undefined, ...filters }}
            parsedQuery={meta.parsedQuery}
          />
          {error ? (
            <p role="alert" className="rounded-md bg-red-50 px-4 py-3 text-sm text-red-800">
              Không thể tải kết quả tìm kiếm lúc này. Vui lòng thử lại sau.
            </p>
          ) : mappedProducts.length > 0 ? (
            <>
              <div className="grid grid-cols-2 gap-x-6 gap-y-10 sm:grid-cols-3 lg:grid-cols-4">
                {mappedProducts.map((product) => (
                  <ProductCard key={product.id} product={product} />
                ))}
              </div>
              <div className="mt-10">
                <Pagination
                  currentPage={currentPage}
                  totalPages={meta.totalPages}
                  baseHref="/tim-kiem"
                  searchParams={{ q: query || undefined, ...filters }}
                />
              </div>
            </>
          ) : (
            <p className="text-ivy-text-muted">
              {isSaleListing
                ? "Hiện chưa có sản phẩm ưu đãi."
                : query
                  ? "Không tìm thấy sản phẩm phù hợp. Hãy thử bỏ bớt một bộ lọc hoặc dùng từ khóa khác."
                  : "Nhập từ khóa để tìm kiếm sản phẩm."}
            </p>
          )}
        </div>
      </div>
    </main>
  );
}
