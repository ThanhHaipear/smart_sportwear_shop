import { browserApiRequest, browserApiRequestEnvelope } from "@/modules/api/browser-client";
import { adminEndpoints } from "@/modules/api/endpoints";
import type { AdminOrderPage, AdminOrderResponse, PageMeta } from "@/modules/orders/types";

export async function listAdminOrdersPage(page = 1, limit = 20, keyword?: string, status?: string, customerId?: string, dateFrom?: string, dateTo?: string, productId?: string): Promise<AdminOrderPage> {
  const response = await browserApiRequestEnvelope<AdminOrderResponse[]>(adminEndpoints.orders, {
    query: { page, limit, keyword, status, customerId, dateFrom, dateTo, productId }, cache: "no-store"
  });
  return { items: response.data, meta: { page, limit, total: 0, totalPages: 0, ...(response.meta as Partial<PageMeta> | undefined) } };
}

export async function fetchOrderDetail(id: string) {
  return browserApiRequest<AdminOrderResponse>(adminEndpoints.orderDetail(id), {
    method: "GET"
  });
}

export async function updateOrderStatus(id: string, input: { status: string; note?: string }) {
  return browserApiRequest<AdminOrderResponse>(adminEndpoints.orderStatus(id), {
    method: "PATCH",
    body: JSON.stringify(input)
  });
}

export async function fetchPackingSlipBatch(orderIds: string[]) {
  return browserApiRequest<AdminOrderResponse[]>(adminEndpoints.packingSlipBatch, {
    method: "POST",
    body: JSON.stringify({ orderIds })
  });
}

export async function processCancellationRefund(id: string) {
  return browserApiRequest<OrderRefundResponse>(adminEndpoints.orderCancellationRefund(id), {
    method: "POST"
  });
}

export type OrderRefundResponse = {
  id: string;
  refundCode: string;
  amount: number;
  provider: string;
  status: string;
  reason: string | null;
  gatewayRequestId: string | null;
  gatewayTransactionNo: string | null;
  manualReference: string | null;
  manualNote: string | null;
  refundedAt: string | null;
  createdAt: string;
};

export async function fetchOrderRefunds(id: string) {
  return browserApiRequest<OrderRefundResponse[]>(adminEndpoints.orderRefunds(id), { method: "GET" });
}

export async function cancelOrderByStaff(id: string, reason: string) {
  return browserApiRequest<AdminOrderResponse>(adminEndpoints.orderCancel(id), {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}

export async function rejectCancellationRequest(id: string, reason: string) {
  return browserApiRequest<AdminOrderResponse>(adminEndpoints.orderCancellationRejection(id), {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}

export async function refreshVnpayRefund(id: string) {
  return browserApiRequest<OrderRefundResponse>(adminEndpoints.adminRefundRefresh(id), { method: "POST" });
}

export async function submitVnpayRefund(id: string) {
  return browserApiRequest<OrderRefundResponse>(adminEndpoints.adminRefundSubmit(id), { method: "POST" });
}

export async function confirmManualRefund(id: string, reference: string, note?: string) {
  return browserApiRequest<OrderRefundResponse>(adminEndpoints.adminRefundManualConfirmation(id), {
    method: "POST",
    body: JSON.stringify({ reference, note: note || null })
  });
}
