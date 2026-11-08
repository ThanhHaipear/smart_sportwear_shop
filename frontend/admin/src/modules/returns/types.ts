export type ReturnItemResponse = {
  id: string;
  orderItemId: string;
  productName: string;
  sku: string;
  quantity: number;
  reason: string | null;
  conditionStatus: string | null;
  resolution: string | null;
  refundAmount: number | null;
};

export type ReturnResponse = {
  id: string;
  orderId: string;
  orderCode: string;
  userId: string;
  returnCode: string;
  status: string;
  reason: string;
  description: string | null;
  requestedAt: string | null;
  approvedAt: string | null;
  receivedAt: string | null;
  resolvedAt: string | null;
  items: ReturnItemResponse[];
  createdAt: string;
  updatedAt: string;
};

export type RefundResponse = {
  id: string;
  returnId: string;
  orderId: string;
  paymentId: string | null;
  refundCode: string;
  amount: number;
  provider: string;
  status: string;
  gatewayRequestId: string | null;
  gatewayTransactionNo: string | null;
  manualReference: string | null;
  manualNote: string | null;
  refundBankCode: string | null;
  refundBankName: string | null;
  refundAccountNumber: string | null;
  refundAccountName: string | null;
  reason: string | null;
  refundedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type ReturnItemResolutionDraft = {
  conditionStatus: "UNOPENED" | "LIKE_NEW" | "USED" | "DAMAGED";
  resolution: "REFUND" | "EXCHANGE" | "STORE_CREDIT" | "REJECT";
  refundAmount: string;
};

export type PageMeta = { page: number; limit: number; total: number; totalPages: number };
export type AdminReturnPage = { items: ReturnResponse[]; meta: PageMeta };
