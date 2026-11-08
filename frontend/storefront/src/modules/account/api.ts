import { apiFetch } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import type {
  AddressResponse,
  OrderResponse,
  PagedResult,
  RefundResponse,
  WishlistResponse,
} from "@/modules/account/types";

export async function getWishlist() {
  const result = await apiFetch<WishlistResponse>(endpoints.wishlist.root);
  return result.data;
}

export async function addWishlistItem(productId: string) {
  const result = await apiFetch<WishlistResponse>(endpoints.wishlist.items, {
    method: "POST",
    body: JSON.stringify({ productId }),
  });
  return result.data;
}

export async function removeWishlistItem(productId: string) {
  const result = await apiFetch<WishlistResponse>(endpoints.wishlist.item(productId), {
    method: "DELETE",
  });
  return result.data;
}

export async function listAddresses() {
  const result = await apiFetch<AddressResponse[]>(endpoints.addresses.root);
  return result.data;
}

export async function createAddress(payload: {
  receiverName: string;
  phone: string;
  province: string;
  // Vietnam's 2025 administrative reform dropped this tier - optional now.
  district?: string;
  ward: string;
  addressLine: string;
  isDefault?: boolean;
}) {
  const result = await apiFetch<AddressResponse>(endpoints.addresses.root, {
    method: "POST",
    body: JSON.stringify(payload),
  });
  return result.data;
}

export async function updateAddress(
  id: string,
  payload: Partial<{
    receiverName: string;
    phone: string;
    province: string;
    district: string;
    ward: string;
    addressLine: string;
  }>,
) {
  const result = await apiFetch<AddressResponse>(endpoints.addresses.item(id), {
    method: "PATCH",
    body: JSON.stringify(payload),
  });
  return result.data;
}

export async function deleteAddress(id: string) {
  await apiFetch(endpoints.addresses.item(id), {
    method: "DELETE",
  });
}

export async function setDefaultAddress(id: string) {
  const result = await apiFetch<AddressResponse>(endpoints.addresses.default(id), {
    method: "PATCH",
  });
  return result.data;
}

export async function listMyOrders(query?: { page?: number; limit?: number; status?: string }) {
  const result = await apiFetch<OrderResponse[]>(endpoints.orders.mine, {
    method: "GET",
    query,
  });
  return result as PagedResult<OrderResponse[]>;
}

export async function getOrderDetail(id: string) {
  const result = await apiFetch<OrderResponse>(endpoints.orders.detail(id));
  return result.data;
}

/**
 * Enforces server-side that the order is actually invoice-eligible (paid,
 * not cancelled) and assigns its invoice number on first call - see
 * OrderService.getOrderInvoice. Throws ApiError on an ineligible order.
 */
export async function getOrderInvoice(id: string) {
  const result = await apiFetch<OrderResponse>(`${endpoints.orders.detail(id)}/invoice`);
  return result.data;
}

export type RefundDestinationInput = {
  bankCode: string;
  bankName: string;
  accountNumber: string;
  accountName: string;
};

export async function cancelOrder(
  id: string,
  input?: { reason?: string } & Partial<RefundDestinationInput>,
) {
  const result = await apiFetch<OrderResponse>(`${endpoints.orders.detail(id)}/cancel`, {
    method: "POST",
    body: JSON.stringify({
      reason: input?.reason || null,
      bankCode: input?.bankCode || null,
      bankName: input?.bankName || null,
      accountNumber: input?.accountNumber || null,
      accountName: input?.accountName || null,
    }),
  });
  return result.data;
}

export async function getOrderRefunds(id: string) {
  const result = await apiFetch<RefundResponse[]>(endpoints.orders.refunds(id), { cache: "no-store" });
  return result.data;
}

export async function updateRefundDestination(id: string, input: RefundDestinationInput) {
  const result = await apiFetch<RefundResponse>(endpoints.orders.refundDestination(id), {
    method: "PUT",
    body: JSON.stringify(input),
  });
  return result.data;
}

export type {
  AddressResponse,
  OrderItem,
  OrderResponse,
  PagedResult,
  RefundResponse,
  WishlistItem,
  WishlistResponse,
} from "@/modules/account/types";
