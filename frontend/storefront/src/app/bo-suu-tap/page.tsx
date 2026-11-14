import Image from "next/image";
import Link from "next/link";
import { Breadcrumb } from "@/components/shared/Breadcrumb";
import { fetchCollections } from "@/modules/category/queries";
import type { CollectionSummary } from "@/modules/category/types";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Bộ sưu tập | Điểm Đến Thể Thao",
  description: "Khám phá các bộ sưu tập thể thao mới tại Điểm Đến Thể Thao.",
};

export default async function CollectionListPage() {
  const collections: CollectionSummary[] = await fetchCollections();

  return (
    <main className="site-main page-below-header flex-1 border-b border-ivy-hairline">
      <Breadcrumb
        items={[
          { label: "Trang chủ", href: "/" },
          { label: "Bộ sưu tập" },
        ]}
      />

      <div className="mx-auto max-w-[1368px] px-4 pb-20 md:px-0">
        <h1 className="mb-12 text-center text-[13px] font-semibold uppercase tracking-[4px] text-ivy-text-muted">
          Bộ sưu tập
        </h1>

        {collections.length === 0 ? (
          <p className="py-20 text-center text-ivy-text-muted">Chưa có bộ sưu tập nào.</p>
        ) : (
          <div className="grid grid-cols-1 gap-10 sm:grid-cols-2 lg:grid-cols-3">
            {collections.map((col) => (
              <Link
                key={col.id}
                href={`/lookbook/${col.slug}`}
                className="group block"
              >
                <div className="relative mb-5 aspect-[3/4] overflow-hidden bg-gray-100">
                  {col.coverImageUrl ? (
                    <Image
                      src={col.coverImageUrl}
                      alt={col.name}
                      fill
                      sizes="(max-width: 640px) 100vw, (max-width: 1024px) 50vw, 33vw"
                      className="object-cover transition-transform duration-500 group-hover:scale-105"
                    />
                  ) : (
                    <div className="flex h-full items-center justify-center text-gray-300 text-sm">
                      Chưa có ảnh
                    </div>
                  )}
                  {col.isFeatured && (
                    <span className="absolute left-4 top-4 bg-ivy-dark px-3 py-1 text-[11px] font-semibold uppercase tracking-wider text-white">
                      Nổi bật
                    </span>
                  )}
                </div>
                <div className="text-center">
                  {(col.season || col.year) && (
                    <p className="mb-1 text-[11px] font-semibold uppercase tracking-[3px] text-ivy-text-muted">
                      {[...new Set([col.season, col.year].filter(Boolean).map(String))].join(" ")}
                    </p>
                  )}
                  <h2 className="mb-2 text-[18px] font-bold uppercase tracking-[1px] text-ivy-dark transition-opacity group-hover:opacity-70">
                    {col.name}
                  </h2>
                  {col.shortDescription && (
                    <p className="text-[13px] leading-6 text-ivy-text-muted line-clamp-2">
                      {col.shortDescription}
                    </p>
                  )}
                  <span className="mt-3 inline-block text-[12px] font-semibold uppercase tracking-[2px] text-ivy-dark underline underline-offset-4">
                    Xem bộ sưu tập
                  </span>
                </div>
              </Link>
            ))}
          </div>
        )}
      </div>
    </main>
  );
}
