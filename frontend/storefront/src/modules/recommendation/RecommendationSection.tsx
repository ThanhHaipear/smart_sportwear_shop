"use client";

import { useEffect, useRef } from "react";
import { ProductCard } from "@/modules/product/components/ProductCard";
import type { Product } from "@/modules/product/types";
import { trackInteraction, trackInteractions } from "@/modules/recommendation/tracking";

export interface RecommendedProduct {
  product: Product;
  algorithm: string | null;
}

interface RecommendationSectionProps {
  title: string;
  sourceProductId: string;
  requestId: string | null;
  modelVersion: string | null;
  items: RecommendedProduct[];
}

// Product grid that reports RECOMMENDATION_IMPRESSION once the block is actually visible and
// RECOMMENDATION_CLICK when a card is opened. Both carry the requestId of the served list.
export function RecommendationSection({ title, sourceProductId, requestId, modelVersion, items }: RecommendationSectionProps) {
  const sectionRef = useRef<HTMLElement>(null);
  const reported = useRef(false);

  useEffect(() => {
    const node = sectionRef.current;
    if (!node || !requestId || items.length === 0 || reported.current) return;
    const report = () => {
      if (reported.current) return;
      reported.current = true;
      // All cards become visible together: send their impressions in ONE batch request.
      trackInteractions(
        items.map((item, position) => ({
          eventType: "RECOMMENDATION_IMPRESSION" as const,
          productId: item.product.id,
          requestId,
          source: "PRODUCT_DETAIL_FBT" as const,
          metadata: { position, algorithm: item.algorithm, modelVersion, sourceProductId },
        })),
      );
    };
    if (typeof IntersectionObserver === "undefined") {
      report();
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          report();
          observer.disconnect();
        }
      },
      { threshold: 0.5 },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [items, modelVersion, requestId, sourceProductId]);

  if (items.length === 0) return null;

  return (
    <section ref={sectionRef} className="mt-16">
      <h2 className="mb-8 text-center text-[20px] font-semibold uppercase tracking-[3px] text-ivy-dark md:text-[42px]">
        {title}
      </h2>
      <div className="grid grid-cols-2 gap-7 sm:grid-cols-3 lg:grid-cols-4">
        {items.map((item, position) => (
          <div
            key={item.product.id}
            // Capture phase: runs before the card's link navigates away.
            onClickCapture={(event) => {
              if (!requestId || !(event.target as HTMLElement).closest("a")) return;
              trackInteraction({
                eventType: "RECOMMENDATION_CLICK",
                productId: item.product.id,
                requestId,
                source: "PRODUCT_DETAIL_FBT",
                metadata: { position, algorithm: item.algorithm, modelVersion, sourceProductId },
              });
            }}
          >
            <ProductCard product={item.product} />
          </div>
        ))}
      </div>
    </section>
  );
}
