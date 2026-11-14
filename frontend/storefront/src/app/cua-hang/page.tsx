import Image from "next/image";
import { Breadcrumb } from "@/components/shared/Breadcrumb";
import { StoreProvinceList } from "@/components/store/StoreProvinceList";
import { StoreMapPanel } from "@/components/store/StoreMapPanel";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Hệ thống cửa hàng | Điểm Đến Thể Thao",
  description: "Tìm cửa hàng Điểm Đến Thể Thao gần bạn và xem thông tin liên hệ.",
};

export default function StoreLocatorPage() {
  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
        <Breadcrumb
          items={[
            { label: "Trang chủ", href: "/" },
            { label: "Danh sách cửa hàng" },
          ]}
        />
        <div className="mx-auto max-w-[1380px] px-4 pb-16">
          <div className="relative mb-10 aspect-[1380/440] overflow-hidden rounded-tl-[80px] rounded-br-[80px]">
            <Image
              src="/images/slideshow_5.webp"
              alt="Hệ thống cửa hàng Điểm Đến Thể Thao"
              fill
              priority
              sizes="(max-width: 1380px) 100vw, 1380px"
              className="object-cover"
            />
          </div>

          <h1 className="mb-8 text-center text-3xl font-semibold tracking-[2px] text-ivy-dark uppercase">
            Hệ thống cửa hàng
          </h1>

          <div className="grid grid-cols-1 gap-10 lg:grid-cols-2">
            <StoreProvinceList />
            <StoreMapPanel />
          </div>
        </div>
      </main>
  );
}


