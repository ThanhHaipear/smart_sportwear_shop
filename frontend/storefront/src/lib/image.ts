import { API_BASE } from "@/lib/api";

const CATALOG_IMAGE_PATH = "/api/v1/catalog-images/";

export function catalogImageUrl(src: string): string {
  return src.startsWith(CATALOG_IMAGE_PATH) ? `${API_BASE}${src}` : src;
}

export function shouldBypassImageOptimization(src: string): boolean {
  // placehold.co serves its extensionless placeholder URLs as SVG. Next.js
  // intentionally refuses to proxy SVGs through the image optimizer.
  // Shopify's CDN can also take longer to respond than Next.js' image proxy
  // timeout in local development. Loading those assets directly avoids a 500
  // from /_next/image while retaining optimization for the other providers.
  return (
    src.startsWith(`${API_BASE}${CATALOG_IMAGE_PATH}`) ||
    src.startsWith("https://placehold.co/") ||
    src.startsWith("https://cdn.shopify.com/")
  );
}
