import { apiFetch } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import type { VisualSearchResult } from "@/modules/product/types";

export type VisualSearchStatus = {
  enabled: boolean;
  available: boolean;
  autoRegion?: boolean;
};

export async function getVisualSearchStatus(signal?: AbortSignal): Promise<VisualSearchStatus> {
  const { data } = await apiFetch<VisualSearchStatus>(endpoints.visualSearchStatus, {
    cache: "no-store",
    signal,
  });
  return data;
}

export async function searchProductsByImage(
  image: Blob,
  limit = 4,
  signal?: AbortSignal,
  autoRegion = false,
): Promise<VisualSearchResult[]> {
  const body = new FormData();
  body.append("image", image, "visual-search.jpg");
  const { data } = await apiFetch<VisualSearchResult[]>(endpoints.visualSearch, {
    method: "POST",
    query: { limit, autoRegion: autoRegion || undefined },
    body,
    signal,
  });
  return data;
}
