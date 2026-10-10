import { browserApiRequest, browserApiRequestEnvelope } from "@/modules/api/browser-client";
import { adminEndpoints } from "@/modules/api/endpoints";
import type { InventoryItemResponse, InventoryPage, InventoryTransactionResponse, PageMeta } from "@/modules/inventory/types";

function toPage<T>(data: T[], meta: Record<string, unknown> | undefined): InventoryPage<T> {
  return { items: data, meta: { page: 1, limit: 0, total: 0, totalPages: 0, ...(meta as Partial<PageMeta> | undefined) } };
}

export async function importStock(input: { variantId: string; quantity: number; note?: string }) {
  return browserApiRequest<InventoryItemResponse>(adminEndpoints.inventoryImport, {
    method: "POST",
    body: JSON.stringify({ ...input, requestId: crypto.randomUUID() })
  });
}

export async function exportStock(input: { variantId: string; quantity: number; note?: string }) {
  return browserApiRequest<InventoryItemResponse>(adminEndpoints.inventoryExport, {
    method: "POST",
    body: JSON.stringify({ ...input, requestId: crypto.randomUUID() })
  });
}

export async function adjustStock(input: { variantId: string; type: string; quantity: number; note?: string }) {
  return browserApiRequest<InventoryItemResponse>(adminEndpoints.inventoryAdjust, {
    method: "POST",
    body: JSON.stringify({ ...input, requestId: crypto.randomUUID() })
  });
}

export async function listInventoryPage(page = 1, limit = 25, keyword?: string) {
  const response = await browserApiRequestEnvelope<InventoryItemResponse[]>(adminEndpoints.inventory, {
    query: { page, limit, keyword }, cache: "no-store"
  });
  return toPage(response.data, response.meta);
}

export async function listInventoryTransactionPage(page = 1, limit = 20) {
  const response = await browserApiRequestEnvelope<InventoryTransactionResponse[]>(adminEndpoints.inventoryTransactions, {
    query: { page, limit }, cache: "no-store"
  });
  return toPage(response.data, response.meta);
}
