"use client";

import { useEffect, useRef, useState } from "react";
import { ProductCard } from "@/modules/product/components/ProductCard";
import type { Product } from "@/modules/product/types";
import { nextPinned, sourceKeyOf, type ServedList } from "@/modules/recommendation/pinning";
import { trackInteraction, trackInteractions, type InteractionSource } from "@/modules/recommendation/tracking";

export interface RecommendedProduct {
  product: Product;
  algorithm: string | null;
}

interface RecommendationSectionProps {
  title: string;
  // One product on the detail page, every product in the cart.
  sourceProductIds: string[];
  source?: InteractionSource;
  // sourceKeyOf(the product IDs the list below was requested for). A list for other sources is ignored.
  responseSourceKey: string;
  requestId: string | null;
  items: RecommendedProduct[];
}

// Product grid that reports RECOMMENDATION_IMPRESSION once the block is actually visible and
// RECOMMENDATION_CLICK when a card is opened. Both carry the requestId of the served list.
//
// The root layout calls router.refresh() periodically, which re-renders the page and serves a NEW
// list with a new requestId. The first list is pinned until the source products change, so the
// customer does not see the block reshuffle and impressions and clicks share one requestId.
// A list requested for other sources (e.g. the previous cart, still loading the new one) is never
// shown or tracked; see pinning.ts.
export function RecommendationSection({
  title, sourceProductIds, source = "PRODUCT_DETAIL_FBT", responseSourceKey, requestId, items,
}: RecommendationSectionProps) {
  const sectionRef = useRef<HTMLElement>(null);
  const reportedFor = useRef<string | null>(null);
  const sourceKey = sourceKeyOf(sourceProductIds);
  const incoming: ServedList<RecommendedProduct> = { sourceKey: responseSourceKey, requestId, items };
  const [pinned, setPinned] = useState<ServedList<RecommendedProduct>>(() => nextPinned(
    { sourceKey: "\u0000", requestId: null, items: [] }, sourceKey, incoming));
  // Derived state (adjusting state while rendering): nextPinned returns the same object when nothing changes.
  const next = nextPinned(pinned, sourceKey, incoming);
  if (next !== pinned) {
    setPinned(next);
  }
  const shown = pinned.items;
  const shownRequestId = pinned.requestId;

  useEffect(() => {
    const node = sectionRef.current;
    if (!node || !shownRequestId || shown.length === 0 || reportedFor.current === shownRequestId) return;
    const report = () => {
      if (reportedFor.current === shownRequestId) return;
      reportedFor.current = shownRequestId;
      // All cards become visible together: send their impressions in ONE batch request.
      trackInteractions(
        shown.map((item, position) => ({
          eventType: "RECOMMENDATION_IMPRESSION" as const,
          productId: item.product.id,
          requestId: shownRequestId,
          source,
          metadata: { position, algorithm: item.algorithm },
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
  }, [shown, shownRequestId, source]);

  if (shown.length === 0) return null;

  return (
    <section ref={sectionRef} className="mt-16">
      <h2 className="mb-8 text-center text-[20px] font-semibold uppercase tracking-[3px] text-ivy-dark md:text-[42px]">
        {title}
      </h2>
      <div className="grid grid-cols-2 gap-7 sm:grid-cols-3 lg:grid-cols-4">
        {shown.map((item, position) => (
          <div
            key={item.product.id}
            // Capture phase: runs before the card's link navigates away.
            onClickCapture={(event) => {
              if (!shownRequestId || !(event.target as HTMLElement).closest("a")) return;
              trackInteraction({
                eventType: "RECOMMENDATION_CLICK",
                productId: item.product.id,
                requestId: shownRequestId,
                source,
                metadata: { position, algorithm: item.algorithm },
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
