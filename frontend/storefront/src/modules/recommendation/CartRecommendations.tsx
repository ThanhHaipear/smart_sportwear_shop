"use client";

import { useEffect, useState } from "react";
import { mapProductListItem } from "@/modules/product/mappers";
import { fetchCartRecommendations, type RecommendationResult } from "@/modules/product/queries";
import { emptyList, sourceKeyOf } from "@/modules/recommendation/pinning";
import { RecommendationSection } from "@/modules/recommendation/RecommendationSection";

const EMPTY: RecommendationResult = emptyList("");

// "You may also need" under the cart: one request per distinct set of products (quantity changes do
// not refetch). Hidden on error or when there is nothing to show, like the detail-page block.
//
// The last response is kept with the key it was requested for; RecommendationSection only shows it while
// that key matches the current cart, so the previous cart's list never appears while a new one loads.
export function CartRecommendations({ productIds }: { productIds: string[] }) {
  const key = sourceKeyOf(productIds);
  const [result, setResult] = useState<RecommendationResult>(EMPTY);

  useEffect(() => {
    let cancelled = false;
    const ids = key ? key.split(",") : [];
    void fetchCartRecommendations(ids).then((next) => {
      // A response for a cart that is no longer current is dropped (key check in the section covers
      // the same case if this guard is ever removed).
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
      responseSourceKey={result.sourceKey}
      requestId={result.requestId}
      modelVersion={result.modelVersion}
      items={items}
    />
  );
}
