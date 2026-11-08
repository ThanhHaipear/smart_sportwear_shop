import { apiFetch } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";

export interface PaymentResponse {
  id: string;
  provider: "COD" | "VNPAY" | "BANK_TRANSFER";
  transactionRef: string;
  amount: number;
  status: "UNPAID" | "PENDING" | "PAID" | "FAILED" | "CANCELLED" | "REFUNDED";
  gatewayTransactionNo?: string | null;
  bankCode?: string | null;
  paidAt?: string | null;
  createdAt: string;
}

export type VietQrPaymentStatus = "UNPAID" | "PENDING" | "PAID" | "FAILED" | "CANCELLED" | "REFUNDED";

export interface VietQrPaymentResponse {
  paymentId: string;
  orderId: string;
  transactionRef: string;
  amount: number;
  currency: string;
  bankBin: string;
  accountNumber: string;
  accountName: string;
  status: VietQrPaymentStatus;
  expiresAt: string;
}

export async function getVietQrPayment(paymentId: string) {
  const result = await apiFetch<VietQrPaymentResponse>(endpoints.payments.vietQr(paymentId), {
    cache: "no-store",
  });
  return result.data;
}

export async function getPaymentsByOrder(orderId: string) {
  const result = await apiFetch<PaymentResponse[]>(endpoints.payments.byOrder(orderId));
  return result.data;
}

export async function processVnpayReturn(params: URLSearchParams) {
  const query: Record<string, string> = {};
  params.forEach((value, key) => {
    if (key.startsWith("vnp_")) query[key] = value;
  });
  if (!query.vnp_SecureHash) return;
  await apiFetch<Record<string, string>>(endpoints.payments.callback, { query, cache: "no-store" });
}
