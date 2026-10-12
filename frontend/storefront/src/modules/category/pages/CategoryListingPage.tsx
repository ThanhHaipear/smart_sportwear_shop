import { Breadcrumb } from "@/components/shared/Breadcrumb";
import { Pagination } from "@/components/shared/Pagination";
import { CategorySidebarFilter } from "@/modules/category/components/CategorySidebarFilter";
import { CategoryToolbar } from "@/modules/category/components/CategoryToolbar";
import { fetchCategoryDetail, fetchCategoryListing } from "@/modules/category/queries";
import type { PageMeta } from "@/modules/category/types";
import { ProductCard } from "@/modules/product/components/ProductCard";
import { mapProductListItem } from "@/modules/product/mappers";
import type { ProductListItem } from "@/modules/product/types";

const PAGE_SIZE = 20;

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

export async function CategoryListingPage({
  params,
  searchParams,
}: {
  params: Promise<{ slug: string }>;
  searchParams: Promise<CategorySearchParams>;
}) {
  const { slug } = await params;
  const resolvedSearchParams = await searchParams;
  const {
    page: pageParam,
    sort,
    sortBy,
    sortOrder,
    size,
    color,
    minPrice,
    maxPrice,
    discount,
    gender,
    sportType,
    brandSlug,
  } = resolvedSearchParams;
  const currentPage = Math.max(1, Number(pageParam ?? 1));

  const [{ products, meta }, category]: [
    { products: ProductListItem[]; meta: PageMeta },
    Awaited<ReturnType<typeof fetchCategoryDetail>>,
  ] = await Promise.all([
    fetchCategoryListing({
      categorySlug: slug,
      page: currentPage,
      limit: PAGE_SIZE,
      sort: sort || undefined,
      sortBy: sort ? undefined : sortBy ?? "createdAt",
      sortOrder: sort ? undefined : sortOrder ?? "desc",
      size: size || undefined,
      color: color || undefined,
      minPrice: minPrice || undefined,
      maxPrice: maxPrice || undefined,
      discount: discount || undefined,
      gender: gender || undefined,
      sportType: sportType || undefined,
      brandSlug: brandSlug || undefined,
    }),
    fetchCategoryDetail(slug),
  ]);

  const mappedProducts = products.map(mapProductListItem);
  const categoryName = category?.name ?? slug.replace(/-/g, " ");
  const breadcrumbItems = [
    { label: "Trang chủ", href: "/" },
    ...(category?.parentSlug
      ? [{
          label: category.parentName ?? category.parentSlug.replace(/-/g, " "),
          href: `/danh-muc/${category.parentSlug}`,
        }]
      : []),
    { label: categoryName },
  ];

  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
      <Breadcrumb items={breadcrumbItems} />
      <div className="mx-auto flex max-w-[1368px] flex-col gap-8 px-4 pb-16 md:px-0 lg:flex-row">
        <CategorySidebarFilter
          key={[size, color, minPrice, maxPrice, discount, gender, sportType, brandSlug].join("|")}
          initialSize={size}
          initialColor={color}
          initialMinPrice={minPrice ? Number(minPrice) : undefined}
          initialMaxPrice={maxPrice ? Number(maxPrice) : undefined}
          initialDiscount={discount}
          initialGender={gender}
          initialSportType={sportType}
          initialBrandSlug={brandSlug}
          sizeType={
            slug === "phu-kien" || slug === "phu-kien-the-thao"
              ? "none"
              : slug.includes("giay")
                ? "shoe"
                : "cloth"
          }
        />
        <div className="flex-1">
          <CategoryToolbar title={categoryName} resultCount={meta.total} currentSort={sort} />
          {mappedProducts.length > 0 ? (
            <>
              <div className="grid grid-cols-2 gap-x-7 gap-y-12 sm:grid-cols-3 lg:grid-cols-4">
                {mappedProducts.map((product) => (
                  <ProductCard key={product.id} product={product} />
                ))}
              </div>
              <div className="mt-10">
                <Pagination
                  currentPage={currentPage}
                  totalPages={meta.totalPages}
                  baseHref={`/danh-muc/${slug}`}
                  searchParams={resolvedSearchParams}
                />
              </div>
            </>
          ) : (
            <p className="border border-ivy-hairline py-10 text-center text-ivy-text-muted">
              Không có sản phẩm nào phù hợp với danh mục và bộ lọc đã chọn.
            </p>
          )}
        </div>
      </div>
    </main>
  );
}
