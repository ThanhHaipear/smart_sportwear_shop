import Image from "next/image";
import { Breadcrumb } from "@/components/shared/Breadcrumb";
import { ContactInfoCards } from "@/components/contact/ContactInfoCards";
import { ContactForm } from "@/components/contact/ContactForm";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Liên hệ | Điểm Đến Thể Thao",
  description: "Liên hệ Điểm Đến Thể Thao để được hỗ trợ sản phẩm, kích thước và đơn hàng.",
};

export default function ContactPage() {
  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
        <Breadcrumb
          items={[
            { label: "Trang chủ", href: "/" },
            { label: "Liên hệ" },
          ]}
        />
        <div className="mx-auto max-w-[1380px] px-4 pb-16">
          <h1 className="mb-8 text-center text-3xl font-semibold uppercase tracking-[2px] text-ivy-dark">Liên hệ</h1>
          <div className="relative mb-10 aspect-[1380/440] overflow-hidden rounded-tl-[80px] rounded-br-[80px]">
            <Image
              src="/images/slideshow_9.webp"
              alt="Liên hệ Điểm Đến Thể Thao"
              fill
              priority
              sizes="(max-width: 1380px) 100vw, 1380px"
              className="object-cover object-center"
            />
          </div>

          <div className="grid grid-cols-1 gap-8 lg:grid-cols-[1fr_1.4fr]">
            <ContactInfoCards />
            <ContactForm />
          </div>

          <div className="mt-16">
            <h2 className="mb-4 text-2xl font-bold text-ivy-dark">
              Hỗ trợ tại cửa hàng
            </h2>
            <p className="max-w-2xl text-sm leading-6 text-ivy-text">
              Điểm Đến Thể Thao cung cấp trang phục, giày và phụ kiện thể thao chính hãng.
              Khách hàng có thể đến hệ thống cửa hàng để thử sản phẩm, nhận tư vấn kích thước
              hoặc kiểm tra tình trạng đơn hàng.
            </p>
          </div>
        </div>
      </main>
  );
}


