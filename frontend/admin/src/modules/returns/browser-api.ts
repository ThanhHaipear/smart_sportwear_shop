import { browserApiRequest, browserApiRequestEnvelope } from "@/modules/api/browser-client";
import { adminEndpoints } from "@/modules/api/endpoints";
import type { AdminReturnPage, PageMeta, RefundResponse, ReturnResponse } from "@/modules/returns/types";

export async function listAdminReturnsPage(page = 1, limit = 10, status?: string): Promise<AdminReturnPage> {
  const response = await browserApiRequestEnvelope<ReturnResponse[]>(adminEndpoints.adminReturns, {
    query: { page, limit, status }, cache: "no-store"
  });
  return { items: response.data, meta: { page, limit, total: 0, totalPages: 0, ...(response.meta as Partial<PageMeta> | undefined) } };
}

export async function fetchRefundsForReturn(id: string) {
  return browserApiRequest<RefundResponse[]>(adminEndpoints.adminReturnRefunds(id), { method: "GET", cache: "no-store" });
}

export async function fetchAdminReturnDetail(id: string) {
  return browserApiRequest<ReturnResponse>(adminEndpoints.adminReturnDetail(id), {
    method: "GET"
  });
}

export async function updateReturnStatus(id: string, input: Record<string, unknown>) {
  return browserApiRequest<ReturnResponse>(adminEndpoints.adminReturnStatus(id), {
    method: "PATCH",
    body: JSON.stringify(input)
  });
}

export async function createRefund(id: string, input?: Record<string, unknown>) {
  return browserApiRequest<RefundResponse>(adminEndpoints.adminReturnRefund(id), {
    method: "POST",
    body: JSON.stringify(input ?? {})
  });
}

export async function updateRefundStatus(id: string, input: Record<string, unknown>) {
  return browserApiRequest<RefundResponse>(adminEndpoints.adminRefundStatus(id), {
    method: "PATCH",
    body: JSON.stringify(input)
  });
}

export async function confirmManualRefund(id: string, reference: string, note?: string) {
  return browserApiRequest<RefundResponse>(adminEndpoints.adminRefundManualConfirmation(id), {
    method: "POST",
    body: JSON.stringify({ reference, note: note || null })
  });
}
