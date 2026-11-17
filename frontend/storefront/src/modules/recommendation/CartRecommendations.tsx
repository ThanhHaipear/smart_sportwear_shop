"use client";

import { useEffect, useState } from "react";
import { mapProductListItem } from "@/modules/product/mappers";
import { fetchCartRecommendations, type RecommendationResult } from "@/modules/product/queries";
import { RecommendationSection } from "@/modules/recommendation/RecommendationSection";

const EMPTY: RecommendationResult = { requestId: null, modelVersion: null, items: [] };

// "You may also need" under the cart: one request per distinct set of products (quantity changes do
// not refetch). Hidden on error or when there is nothing to show, like the detail-page block.
export function CartRecommendations({ productIds }: { productIds: string[] }) {
  const key = Array.from(new Set(productIds)).sort().join(",");
  const [result, setResult] = useState<RecommendationResult>(EMPTY);

  useEffect(() => {
    let cancelled = false;
    const ids = key ? key.split(",") : [];
    void fetchCartRecommendations(ids).then((next) => {
      if (!cancelled) setResult(next);
    });
    return () => {
      cancelled = true;
    };
  }, [key]);

  const items = result.items
    .slice(0, 4)
    .map((item) => ({ product: mapProductListItem(item.product), algorithm: item.algorithm }));

  return (
    <RecommendationSection
      title="Có thể bạn cũng cần"
      sourceProductIds={key ? key.split(",") : []}
      source="CART_RECOMMENDATION"
      requestId={result.requestId}
      modelVersion={result.modelVersion}
      items={items}
    />
  );
}
