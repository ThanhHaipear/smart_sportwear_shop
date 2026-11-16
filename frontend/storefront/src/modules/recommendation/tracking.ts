"use client";

import { apiFetch } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";

// Behaviour events consumed by recommendation-service (`recommendation.user_interactions`).
// Tracking must never break the shop: every failure is swallowed.

export type InteractionType =
  | "VIEW"
  | "ADD_TO_CART"
  | "WISHLIST"
  | "RECOMMENDATION_IMPRESSION"
  | "RECOMMENDATION_CLICK";

export type InteractionSource = "PRODUCT_DETAIL" | "PRODUCT_DETAIL_FBT";

export interface InteractionInput {
  eventType: InteractionType;
  productId: string;
  source: InteractionSource;
  variantId?: string;
  requestId?: string;
  // Never put personal data here (name, email, phone, address).
  metadata?: Record<string, string | number | boolean | null>;
}

const ANONYMOUS_ID_KEY = "sss_anonymous_id";
const VIEW_KEY_PREFIX = "sss_viewed:";
const VIEW_THROTTLE_MS = 10 * 60 * 1000;
const DATA_SOURCE = process.env.NEXT_PUBLIC_INTERACTION_DATA_SOURCE === "DEMO" ? "DEMO" : "REAL";
const TRACKING_ENABLED = process.env.NEXT_PUBLIC_INTERACTION_TRACKING !== "false";

function newId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0;
    return (char === "x" ? random : (random & 0x3) | 0x8).toString(16);
  });
}

function safeStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

// Stable per-browser pseudonymous ID so guest behaviour can be linked over time.
export function getAnonymousId(): string {
  const storage = safeStorage();
  const existing = storage?.getItem(ANONYMOUS_ID_KEY);
  if (existing) return existing;
  const created = newId();
  try {
    storage?.setItem(ANONYMOUS_ID_KEY, created);
  } catch {
    // Private mode: fall back to a per-page-load ID.
  }
  return created;
}

function buildEvent(input: InteractionInput, anonymousId: string) {
  const eventId = newId();
  return {
    eventId,
    eventType: input.eventType,
    eventVersion: 1,
    occurredAt: new Date().toISOString(),
    traceId: eventId,
    anonymousId,
    productId: input.productId,
    variantId: input.variantId ?? null,
    requestId: input.requestId ?? null,
    source: input.source,
    dataSource: DATA_SOURCE,
    metadata: input.metadata ?? {},
  };
}

function send(path: string, body: unknown): void {
  // keepalive lets a click event finish even when the browser navigates away immediately.
  void apiFetch(path, {
    method: "POST",
    body: JSON.stringify(body),
    keepalive: true,
    cache: "no-store",
  }).catch(() => undefined);
}

export function trackInteraction(input: InteractionInput): void {
  if (!TRACKING_ENABLED || typeof window === "undefined") return;
  send(endpoints.recommendationInteractions, buildEvent(input, getAnonymousId()));
}

// Server limit per batch (recommendation-service MAX_BATCH_EVENTS).
const MAX_BATCH_EVENTS = 10;

// Sends several events that happen together (e.g. all impressions of one recommendation list)
// in one request instead of one request per event. Each event is still stored as its own row.
export function trackInteractions(inputs: InteractionInput[]): void {
  if (!TRACKING_ENABLED || typeof window === "undefined" || inputs.length === 0) return;
  if (inputs.length === 1) {
    trackInteraction(inputs[0]);
    return;
  }
  const anonymousId = getAnonymousId();
  for (let start = 0; start < inputs.length; start += MAX_BATCH_EVENTS) {
    const events = inputs.slice(start, start + MAX_BATCH_EVENTS).map((input) => buildEvent(input, anonymousId));
    send(endpoints.recommendationInteractionsBatch, { events });
  }
}

// VIEW is recorded at most once per product per browser every 10 minutes to avoid refresh spam.
export function trackProductView(productId: string): void {
  const storage = safeStorage();
  const key = VIEW_KEY_PREFIX + productId;
  const now = Date.now();
  const last = Number(storage?.getItem(key) ?? 0);
  if (Number.isFinite(last) && now - last < VIEW_THROTTLE_MS) return;
  try {
    storage?.setItem(key, String(now));
  } catch {
    // ignore storage quota/private mode
  }
  trackInteraction({ eventType: "VIEW", productId, source: "PRODUCT_DETAIL" });
}
