// Which recommendation list a block may show. Pure functions (no React, no imports) so they can be unit
// tested with `node --test`.
//
// Every served list is tagged with the source products it was requested for. A list is shown, and its
// impressions and clicks are tracked, only while that tag matches the block's current sources: a slow or
// late response for an earlier cart can never appear under, or be attributed to, a different cart.

export interface ServedList<T> {
  // Canonical key of the source product IDs the request was made with (see sourceKeyOf).
  sourceKey: string;
  requestId: string | null;
  modelVersion: string | null;
  items: T[];
}

// Order-independent key of a set of product IDs: distinct, sorted, comma-joined. Quantity changes and
// reordering keep the key; adding or removing a product changes it.
export function sourceKeyOf(productIds: readonly string[]): string {
  return Array.from(new Set(productIds)).sort().join(",");
}

export function emptyList<T>(sourceKey: string): ServedList<T> {
  return { sourceKey, requestId: null, modelVersion: null, items: [] };
}

// Next pinned list given the current sources and the list just received from props.
//
// - A list for other sources is treated as "nothing yet" (never shown, never tracked).
// - Sources changed: start over with the incoming list for the new sources (possibly empty while loading).
// - Same sources: keep the pinned list, so a periodic router.refresh() serving a new requestId does not
//   reshuffle the block; only fill it when nothing was served yet.
//
// Returns `pinned` itself when nothing changes, so callers can compare by identity.
export function nextPinned<T>(pinned: ServedList<T>, currentKey: string, incoming: ServedList<T>): ServedList<T> {
  const usable = incoming.sourceKey === currentKey ? incoming : emptyList<T>(currentKey);
  if (pinned.sourceKey !== currentKey) return usable;
  if (!pinned.requestId && usable.requestId) return usable;
  return pinned;
}
