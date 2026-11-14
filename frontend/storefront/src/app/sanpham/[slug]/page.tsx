import { ProductDetailScreen } from "@/modules/product/pages/ProductDetailScreen";

import { fetchProductDetail } from "@/modules/product/queries";
import type { Metadata } from "next";

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const product = await fetchProductDetail(slug).catch(() => null);
  return {
    title: product ? `${product.name} | Điểm Đến Thể Thao` : "Sản phẩm | Điểm Đến Thể Thao",
    description: product?.description || "Thông tin sản phẩm thể thao chính hãng tại Điểm Đến Thể Thao.",
  };
}

export default async function ProductDetailPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  return <ProductDetailScreen params={params} />;
}
