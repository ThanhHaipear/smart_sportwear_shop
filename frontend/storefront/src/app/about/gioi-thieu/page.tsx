import { Breadcrumb } from "@/components/shared/Breadcrumb";
import Image from "next/image";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Giới thiệu | Điểm Đến Thể Thao",
  description: "Tìm hiểu về Điểm Đến Thể Thao và cam kết cung cấp sản phẩm thể thao chính hãng.",
};

// The live page's entire "story" (CEO quote, timeline, stats, core values,
// activity gallery) is composed of full-bleed editorial images with text
// baked into the pixels — this route is faithfully just an ordered stack
// of those real images.
export default function AboutPage() {
  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
      <Breadcrumb
        items={[
          { label: "Trang chủ", href: "/" },
          { label: "Giới thiệu về Điểm Đến Thể Thao" },
        ]}
      />
      <div className="mx-auto max-w-[1380px] px-4 pb-16">
        <h1 className="mb-8 text-center text-3xl font-semibold uppercase tracking-[2px] text-ivy-dark md:text-5xl">Điểm Đến Thể Thao</h1>
        <div className="relative mb-10 aspect-[16/6] overflow-hidden rounded-tl-[80px] rounded-br-[80px]">
          <Image src="/images/banner-hero-1.png" alt="Sản phẩm thể thao chính hãng" fill priority className="object-cover" />
        </div>
        <div className="grid gap-8 md:grid-cols-3">
          {[
            ["Sản phẩm chính hãng", "Lựa chọn trang phục, giày và phụ kiện thể thao từ các thương hiệu uy tín."],
            ["Tư vấn phù hợp", "Hỗ trợ khách hàng chọn đúng sản phẩm và kích thước cho nhu cầu tập luyện."],
            ["Mua sắm thuận tiện", "Kết nối trải nghiệm trực tuyến với hệ thống cửa hàng và dịch vụ giao hàng."],
          ].map(([title, description]) => (
            <section key={title} className="rounded-2xl border border-ivy-hairline p-6">
              <h2 className="mb-3 text-xl font-semibold text-ivy-dark">{title}</h2>
              <p className="text-sm leading-6 text-ivy-text">{description}</p>
            </section>
          ))}
        </div>
      </div>
    </main>
  );
}
