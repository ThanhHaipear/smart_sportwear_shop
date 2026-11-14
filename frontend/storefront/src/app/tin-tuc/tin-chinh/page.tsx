import { Breadcrumb } from "@/components/shared/Breadcrumb";
import { LifestyleHeader } from "@/components/editorial/LifestyleHeader";
import { FeaturedStoryCard } from "@/components/editorial/FeaturedStoryCard";
import { MagazineGrid } from "@/components/editorial/MagazineGrid";
import { ProductRail } from "@/modules/product/components/ProductRail";
import { mapProductListItem } from "@/modules/product/mappers";
import { fetchNewestProducts } from "@/modules/product/queries";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Tin tức thể thao | Điểm Đến Thể Thao",
  description: "Cẩm nang lựa chọn trang phục, giày và phụ kiện thể thao.",
};

export default async function LifestyleNewsPage() {
  const newestProducts = await fetchNewestProducts(8).catch(() => []);
  const trending = newestProducts.map(mapProductListItem);

  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
      <Breadcrumb
        items={[
          { label: "Trang chủ", href: "/" },
          { label: "Tin tức" },
        ]}
      />
      <div className="mx-auto max-w-[1380px] px-4 pb-16">
        <LifestyleHeader activeSlug="tin-chinh" />

        <FeaturedStoryCard
          image="/images/banner-hero-1.png"
          eyebrow="CẨM NANG"
          title="CHỌN TRANG PHỤC THỂ THAO PHÙ HỢP VỚI BẠN"
          excerpt="Ưu tiên chất liệu thoáng khí, độ co giãn phù hợp và phom dáng không cản trở chuyển động để tập luyện thoải mái hơn."
          date="Hướng dẫn mua sắm"
          href="/danh-muc/ao-the-thao"
        />
        <FeaturedStoryCard
          image="/images/slideshow_10.webp"
          eyebrow="KINH NGHIỆM"
          title="BẢO QUẢN ĐỒ THỂ THAO BỀN ĐẸP SAU MỖI BUỔI TẬP"
          excerpt="Giặt sớm sau khi sử dụng, hạn chế nhiệt độ cao và phơi ở nơi thông thoáng giúp chất liệu giữ được độ co giãn và màu sắc."
          date="Hướng dẫn sử dụng"
          href="/about/gioi-thieu"
          reverse
        />

        <MagazineGrid />

        {trending.length > 0 && (
          <section className="mt-8">
            <ProductRail title="MODERN TRENDING" products={trending} />
          </section>
        )}
      </div>
    </main>
  );
}
