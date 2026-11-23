import { apiFetch } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import type { BannerSlide } from "@/components/marketing/types";
import type { ActivePromotion, Banner, ProductDetail, ProductListItem } from "@/modules/product/types";
import { emptyList, sourceKeyOf } from "@/modules/recommendation/pinning";

type ProductQuery = Record<string, string | number | boolean | undefined>;

export async function fetchProducts(query: ProductQuery): Promise<ProductListItem[]> {
  try {
    const result = await apiFetch<ProductListItem[]>(endpoints.products, {
      query,
      cache: "no-store"
    });
    return result.data ?? [];
  } catch {
    return [];
  }
}

export async function fetchNewestProducts(limit = 12) {
  return fetchProducts({ limit, sortBy: "createdAt", sortOrder: "desc" });
}

export async function fetchFeaturedProducts(limit = 12) {
  return fetchProducts({ featured: true, limit, sortBy: "createdAt", sortOrder: "desc" });
}

export async function fetchBestSellingProducts(limit = 12) {
  return fetchProducts({ limit, sort: "bestselling" });
}

export async function fetchSaleProducts(limit = 12) {
  return fetchProducts({ discount: "any", limit, sortBy: "createdAt", sortOrder: "desc" });
}

export async function fetchActivePromotions(): Promise<ActivePromotion[]> {
  try {
    const result = await apiFetch<ActivePromotion[]>(endpoints.activePromotions, {
      cache: "no-store"
    });
    return result.data ?? [];
  } catch {
    return [];
  }
}

export async function fetchProductDetail(slug: string): Promise<ProductDetail | null> {
  try {
    const result = await apiFetch<ProductDetail>(endpoints.product(slug), {
      cache: "no-store"
    });
    return result.data;
  } catch {
    return null;
  }
}

type RecommendationResponse = {
  requestId?: string;
  modelVersion?: string;
  type: string;
  items: Array<{ product: ProductListItem; algorithm?: string }>;
};

export interface RecommendationResult {
  // sourceKeyOf(the product IDs this list was requested for); a block only shows it for those sources.
  sourceKey: string;
  // Identifies this served list; impression/click events must carry it to measure CTR per model.
  requestId: string | null;
  modelVersion: string | null;
  items: Array<{ product: ProductListItem; algorithm: string | null }>;
}

// "Frequently bought together" (association rules + popularity fallback). Empty on any
// error so the section just hides.
export async function fetchFrequentlyBoughtTogether(productId: string): Promise<RecommendationResult> {
  const sourceKey = sourceKeyOf([productId]);
  try {
    const result = await apiFetch<RecommendationResponse>(endpoints.frequentlyBoughtTogether(productId), {
      cache: "no-store"
    });
    return {
      sourceKey,
      requestId: result.data?.requestId ?? null,
      modelVersion: result.data?.modelVersion ?? null,
      items: (result.data?.items ?? [])
        .filter((item) => item?.product)
        .map((item) => ({ product: item.product, algorithm: item.algorithm ?? null })),
    };
  } catch {
    return emptyList(sourceKey);
  }
}

// Recommendations for the whole cart (association rules -> content -> popularity). Empty on any error.
export async function fetchCartRecommendations(productIds: string[], limit = 4): Promise<RecommendationResult> {
  const sourceKey = sourceKeyOf(productIds);
  if (productIds.length === 0) return emptyList(sourceKey);
  try {
    const result = await apiFetch<RecommendationResponse>(endpoints.cartRecommendations, {
      method: "POST",
      body: JSON.stringify({ sourceProductIds: productIds, limit }),
      cache: "no-store"
    });
    return {
      sourceKey,
      requestId: result.data?.requestId ?? null,
      modelVersion: result.data?.modelVersion ?? null,
      items: (result.data?.items ?? [])
        .filter((item) => item?.product)
        .map((item) => ({ product: item.product, algorithm: item.algorithm ?? null })),
    };
  } catch {
    return emptyList(sourceKey);
  }
}

export async function fetchHomeBannerSlides(): Promise<BannerSlide[]> {
  try {
    const result = await apiFetch<Banner[]>(endpoints.banners, {
      query: { placement: "HOME_HERO" },
      cache: "no-store"
    });
    const hero = result.data.find((banner) => banner.placement === "HOME_HERO");
    if (!hero) return [];

    return hero.items
      .filter((item) => item.isActive)
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((item) => ({
        image: item.imageUrl,
        href: item.targetUrl ?? "/",
        alt: item.title ?? "Banner",
      }));
  } catch {
    return [];
  }
}
