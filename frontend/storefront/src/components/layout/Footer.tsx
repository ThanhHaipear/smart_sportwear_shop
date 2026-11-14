"use client";

import Image from "next/image";
import Link from "next/link";
import {
  FOOTER_CONTACT_LINKS,
  FOOTER_CUSTOMER_SERVICE_LINKS,
  FOOTER_INTRO_LINKS,
} from "@/modules/content/data/layout";
import type { FooterLinkItem } from "@/modules/content/types";

function FooterLinkList({ links }: { links: FooterLinkItem[] }) {
  return (
    <ul className="flex flex-col gap-2.5">
      {links.map((link) => (
        <li key={link.label}>
          <a
            href={link.href}
            {...(link.external ? { target: "_blank", rel: "nofollow" } : {})}
            className="text-[14px] leading-6 text-ivy-text transition-colors hover:text-ivy-dark"
          >
            {link.label}
          </a>
        </li>
      ))}
    </ul>
  );
}

export function Footer() {
  return (
    <footer className="border-t border-ivy-hairline">
      <div className="container mx-auto max-w-[1368px] px-4 py-12 md:px-0">
        <div className="grid gap-10 lg:grid-cols-[220px_1fr_320px] lg:gap-8">
          <div className="flex flex-col items-start gap-4">
            <Image src="/images/logo-v2.png" alt="Điểm Đến Thể Thao" width={120} height={80} className="h-auto" />
            <p className="text-[14px] leading-6 text-ivy-text">
              Trang phục, giày và phụ kiện thể thao chính hãng cho mọi hành trình vận động.
            </p>
          </div>

          <div className="grid gap-8 sm:grid-cols-3">
            <div className="flex flex-col gap-4 sm:flex-1">
              <h3 className="text-[24px] leading-[1.2] font-semibold text-ivy-dark">Giới thiệu</h3>
              <FooterLinkList links={FOOTER_INTRO_LINKS} />
            </div>
            <div className="flex flex-col gap-4 sm:flex-1">
              <h3 className="text-[24px] leading-[1.2] font-semibold text-ivy-dark">Dịch vụ khách hàng</h3>
              <FooterLinkList links={FOOTER_CUSTOMER_SERVICE_LINKS} />
            </div>
            <div className="flex flex-col gap-4 sm:flex-1">
              <h3 className="text-[24px] leading-[1.2] font-semibold text-ivy-dark">Liên hệ</h3>
              <FooterLinkList links={FOOTER_CONTACT_LINKS} />
            </div>
          </div>

          <div className="rounded-2xl bg-[#f7f8f9] p-6">
            <h3 className="mb-3 text-[24px] leading-[1.2] font-semibold text-ivy-dark">Cần hỗ trợ?</h3>
            <p className="mb-5 text-[14px] leading-6 text-ivy-text">
              Đội ngũ tư vấn sẵn sàng hỗ trợ chọn sản phẩm, kích thước và kiểm tra đơn hàng.
            </p>
            <div className="flex flex-col gap-3">
              <a href="tel:02438341002" className="rounded-full bg-ivy-dark px-5 py-3 text-center text-[14px] font-medium text-white">
                Gọi 0243 834 1002
              </a>
              <Link href="/lien-he" className="rounded-full border border-ivy-dark px-5 py-3 text-center text-[14px] font-medium text-ivy-dark">
                Gửi yêu cầu hỗ trợ
              </Link>
            </div>
          </div>
        </div>
      </div>

      <div className="border-t border-ivy-hairline py-5">
        <p className="text-center text-[13px] text-ivy-text">© Điểm Đến Thể Thao. Bảo lưu mọi quyền.</p>
      </div>
    </footer>
  );
}
