import { Suspense } from "react";
import { BrandPromoCarousel } from "@/components/marketing/BrandPromoCarousel";
import { FeaturedCollections } from "@/components/marketing/FeaturedCollections";
import { HomeBanner } from "@/components/marketing/HomeBanner";
import { HomeCategoryGrid } from "@/components/marketing/HomeCategoryGrid";
import { HomeFlashSale } from "@/components/marketing/HomeFlashSale";
import { HomeTeamSelector } from "@/components/marketing/HomeTeamSelector";
import { HomeUspStrip } from "@/components/marketing/HomeUspStrip";
import { HomeVisualSearchBanner } from "@/components/marketing/HomeVisualSearchBanner";
import { fetchCollections } from "@/modules/category/queries";
import { ProductRail } from "@/modules/product/components/ProductRail";
import { mapProductListItem } from "@/modules/product/mappers";
import {
  fetchActivePromotions,
  fetchBestSellingProducts,
  fetchFeaturedProducts,
  fetchHomeBannerSlides,
  fetchNewestProducts,
  fetchSaleProducts,
} from "@/modules/product/queries";

async function HeroContent() {
  return <HomeBanner slides={await fetchHomeBannerSlides()} />;
}

async function FlashSaleContent() {
  const [promotions, products] = await Promise.all([fetchActivePromotions(), fetchSaleProducts(12)]);
  return (
    <HomeFlashSale
      promotion={promotions[0] ?? null}
      products={products.map((product) => ({ ...mapProductListItem(product), ribbon: "sale" }))}
    />
  );
}

async function PopularProductsContent() {
  const [bestSelling, featured] = await Promise.all([fetchBestSellingProducts(12), fetchFeaturedProducts(12)]);
  if (bestSelling.length === 0 && featured.length === 0) return null;
  return (
    <ProductRail
      title="Sản phẩm được yêu thích"
      tabs={[
        {
          id: "best-selling",
          label: "Bán chạy",
          products: bestSelling.map((product) => ({
            ...mapProductListItem(product),
            ribbon: "bestseller" as const,
          })),
        },
        { id: "featured", label: "Nổi bật", products: featured.map(mapProductListItem) },
      ].filter((tab) => tab.products.length > 0)}
    />
  );
}

async function CollectionsContent() {
  return <FeaturedCollections collections={await fetchCollections()} />;
}

async function NewProductsContent() {
  const products = await fetchNewestProducts(20);
  return <ProductRail title="Sản phẩm mới" products={products.map(mapProductListItem)} />;
}

export function HomeScreen() {
  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
      <h1 className="sr-only">Điểm Đến Thể Thao - Đồ thể thao chính hãng</h1>
      <Suspense fallback={null}><HeroContent /></Suspense>
      <HomeUspStrip />
      <div className="mx-auto max-w-342 px-4 md:px-0">
        <HomeCategoryGrid />
        <Suspense fallback={null}><FlashSaleContent /></Suspense>
        <HomeTeamSelector />
        <Suspense fallback={null}><PopularProductsContent /></Suspense>
        <Suspense fallback={null}><CollectionsContent /></Suspense>
        <Suspense fallback={null}><NewProductsContent /></Suspense>
      </div>
      <HomeVisualSearchBanner />
      <BrandPromoCarousel />
    </main>
  );
}
