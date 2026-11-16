"use client";

import { useEffect } from "react";
import { trackProductView } from "@/modules/recommendation/tracking";

// Renders nothing; records a VIEW once the product detail page is shown in the browser.
export function ProductViewTracker({ productId }: { productId: string }) {
  useEffect(() => {
    trackProductView(productId);
  }, [productId]);
  return null;
}
