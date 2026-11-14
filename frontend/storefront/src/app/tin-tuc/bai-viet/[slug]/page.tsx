import { Breadcrumb } from "@/components/shared/Breadcrumb";
import { ArticleCategorySidebar } from "@/components/editorial/ArticleCategorySidebar";
import { ArticleContent } from "@/components/editorial/ArticleContent";
import { ArticleRelatedSidebar } from "@/components/editorial/ArticleRelatedSidebar";
import { ProductRail } from "@/modules/product/components/ProductRail";
import { mapProductListItem } from "@/modules/product/mappers";
import { fetchNewestProducts } from "@/modules/product/queries";
import { ARTICLE_DETAIL_BLOCKS, ARTICLE_DETAIL_TAGS } from "@/modules/content/data/editorial";

export default async function ArticleDetailPage() {
  const newestProducts = await fetchNewestProducts(8);
  const newArrivals = newestProducts.map(mapProductListItem);

  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
      <Breadcrumb
        items={[
          { label: "Trang chủ", href: "/" },
          { label: "Tin tức", href: "/tin-tuc/tin-chinh" },
          { label: "Hướng dẫn chọn trang phục thể thao" },
        ]}
      />
      <div className="mx-auto max-w-[1380px] px-4 pb-16">
        <div className="grid grid-cols-1 gap-10 lg:grid-cols-[220px_1fr_280px]">
          <ArticleCategorySidebar />
          <ArticleContent
            title="HƯỚNG DẪN CHỌN TRANG PHỤC THỂ THAO PHÙ HỢP"
            date="Cẩm nang sản phẩm"
            blocks={ARTICLE_DETAIL_BLOCKS}
            tags={ARTICLE_DETAIL_TAGS}
          />
          <ArticleRelatedSidebar />
        </div>
      </div>

      {newArrivals.length > 0 && (
        <div className="mx-auto max-w-[1380px] px-4 pb-16">
          <ProductRail title="SẢN PHẨM MỚI" products={newArrivals} />
        </div>
      )}
    </main>
  );
}
