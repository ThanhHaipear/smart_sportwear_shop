export interface WishlistItem {
  id: string;
  productId: string;
  productName: string;
  thumbnail?: string | null;
  createdAt: string;
}

export interface WishlistResponse {
  id: string;
  items: WishlistItem[];
}

export interface AddressResponse {
  id: string;
  receiverName: string;
  phone: string;
  province: string;
  // Vietnam's 2025 administrative reform dropped this tier - null on new addresses.
  district: string | null;
  ward: string;
  addressLine: string;
  isDefault: boolean;
  createdAt: string;
}

export interface OrderItem {
  id: string;
  productId: string;
  variantId: string;
  productName: string;
  sku: string;
  size?: string | null;
  color?: string | null;
  thumbnail?: string | null;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface ShippingAddressResponse {
  receiverName: string | null;
  phone: string | null;
  province: string | null;
  district: string | null;
  ward: string | null;
  addressLine: string | null;
}

export interface OrderResponse {
  id: string;
  orderCode: string;
  orderStatus: string;
  paymentStatus: string;
  paymentMethod: "COD" | "VNPAY" | "VIETQR";
  subtotalAmount: number;
  shippingFee: number;
  discountAmount: number;
  totalAmount: number;
  note?: string | null;
  cancellationRequestedBy?: "CUSTOMER" | "STAFF" | null;
  cancellationReason?: string | null;
  cancellationRequestedAt?: string | null;
  items: OrderItem[];
  shippingAddress?: ShippingAddressResponse | null;
  invoiceNumber?: string | null;
  invoiceRequested: boolean;
  invoiceCompanyName?: string | null;
  invoiceTaxCode?: string | null;
  invoiceCompanyAddress?: string | null;
  createdAt: string;
}

export interface PageMeta {
  page: number;
  limit: number;
  total: number;
  totalPages: number;
}

export interface PagedResult<T> {
  data: T;
  meta?: PageMeta;
}
