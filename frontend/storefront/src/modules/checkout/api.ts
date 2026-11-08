import { apiFetch } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import type { OrderResponse } from "@/modules/account/types";
import type { CheckoutPreviewResponse } from "@/modules/checkout/types";

export async function previewCheckout(
  addressId?: string,
  cartItemIds?: string[],
  buyNow?: { variantId: string; quantity: number } | null,
) {
  const result = await apiFetch<CheckoutPreviewResponse>(endpoints.checkout.preview, {
    method: "POST",
    body: JSON.stringify({
      addressId: addressId || null,
      cartItemIds: cartItemIds?.length ? cartItemIds : null,
      buyNowVariantId: buyNow?.variantId ?? null,
      buyNowQuantity: buyNow?.quantity ?? null,
    }),
  });
  return result.data;
}

export async function createOrder(payload: {
  addressId: string;
  paymentMethod: "COD" | "VIETQR";
  note?: string;
  cartItemIds?: string[];
  buyNowVariantId?: string;
  buyNowQuantity?: number;
  invoiceRequested?: boolean;
  invoiceCompanyName?: string;
  invoiceTaxCode?: string;
  invoiceCompanyAddress?: string;
}) {
  const result = await apiFetch<OrderResponse>(endpoints.orders.root, {
    method: "POST",
    body: JSON.stringify(payload),
  });
  return result.data;
}

export async function createVnpayPayment(orderId: string) {
  const result = await apiFetch<{ paymentUrl: string; transactionRef: string }>(endpoints.payments.create, {
    method: "POST",
    body: JSON.stringify({ orderId }),
  });
  return result.data;
}

export async function createVietQrPayment(orderId: string) {
  const result = await apiFetch<import("@/modules/payment/api").VietQrPaymentResponse>(
    endpoints.payments.createVietQr,
    {
      method: "POST",
      body: JSON.stringify({ orderId }),
    },
  );
  return result.data;
}

export type { CheckoutItemPreview, CheckoutPreviewResponse } from "@/modules/checkout/types";
