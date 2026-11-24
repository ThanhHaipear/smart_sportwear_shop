import { catalogImageUrl } from "@/lib/image";
import type { Product, ProductDetail, ProductListItem } from "@/modules/product/types";
import { NO_IMAGE } from "@/modules/ui/placeholder";

export function mapProductListItem(p: ProductListItem): Product {
  const image = catalogImageUrl(p.thumbnail ?? NO_IMAGE);
  return {
    id: p.id,
    name: p.name,
    href: `/sanpham/${p.slug}`,
    image,
    hoverImage: image,
    price: p.salePrice ?? p.minPrice,
    oldPrice: p.compareAtPrice,
    discountPercent: p.discountPercent,
    isOutOfStock: p.availableQuantity <= 0,
    colors: [],
    sizes: [],
  };
}

export function mapProductDetail(p: ProductDetail): {
  id: string;
  name: string;
  slug: string;
  href: string;
  images: Array<{ url: string; color: string | null }>;
  price: number;
  compareAtPrice?: number;
  sku?: string;
  description?: string;
  colors: Array<{ id: string; image: string; label: string; active: boolean }>;
  sizes: Array<{ id: string; label: string; quantity: number; disabled?: boolean }>;
  reviewSummary?: ProductDetail["reviewSummary"];
  relatedProducts: ReturnType<typeof mapProductListItem>[];
} {
  const sortedImages = [...p.images].sort((a, b) => a.sortOrder - b.sortOrder);
  const images = sortedImages.map((img) => ({ url: catalogImageUrl(img.imageUrl), color: img.color ?? null }));

  // Distinct colors in first-seen order; each swatch uses that color's own first image.
  const colorLabels: string[] = [];
  for (const v of p.variants) {
    if (v.color && !colorLabels.includes(v.color)) colorLabels.push(v.color);
  }
  const uniqueColors = colorLabels.map((label, idx) => {
    const swatch = sortedImages.find((img) => img.color === label) ?? sortedImages[idx];
    return {
      id: label,
      image: catalogImageUrl(swatch?.imageUrl ?? NO_IMAGE),
      label,
      active: idx === 0,
    };
  });

  const uniqueSizes = Array.from(
    new Map(
      p.variants
        .filter((v) => v.size)
        .map((v) => [v.size, v]),
    ).values(),
  ).map((v) => ({
    id: v.id,
    label: v.size ?? "",
    quantity: v.availableQuantity,
    disabled: v.availableQuantity === 0,
  }));

  return {
    id: p.id,
    name: p.name,
    slug: p.slug,
    href: `/sanpham/${p.slug}`,
    images,
    price: p.variants[0]?.price ?? p.variants.reduce((min, v) => Math.min(min, v.price), Infinity),
    compareAtPrice: p.variants[0]?.compareAtPrice,
    sku: p.variants[0]?.sku?.split("-")[0],
    description: p.description,
    colors: uniqueColors,
    sizes: uniqueSizes,
    reviewSummary: p.reviewSummary,
    relatedProducts: p.relatedProducts.map(mapProductListItem),
  };
}
