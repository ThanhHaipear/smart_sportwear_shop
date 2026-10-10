"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { cancelOrder, getOrderDetail } from "@/modules/account/api";
import type { OrderResponse } from "@/modules/account/types";
import { getApiErrorMessage } from "@/lib/api-errors";
import { getAccessToken } from "@/lib/session";
import { createVnpayPayment } from "@/modules/checkout/api";
import { getOrderStatusLabel, isInvoiceEligible } from "@/modules/account/order-labels";
import { CUSTOMER_ORDER_CHANGED_EVENT } from "@/modules/notifications/types";
import { InvoicePrintLink } from "@/modules/account/components/InvoicePrintLink";

const money = (n: number) => `${n.toLocaleString("vi-VN")}đ`;

export function OrderDetailPageClient({ orderId }: { orderId: string }) {
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [paying, setPaying] = useState(false);

  const load = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    setError(null);
    try {
      setOrder(await getOrderDetail(orderId));
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể tải chi tiết đơn hàng."));
    } finally {
      if (showLoading) setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    // Defer off the effect body so no setState runs synchronously inline.
    const timer = setTimeout(() => {
      if (!getAccessToken()) {
        setLoading(false);
        setError("Vui lòng đăng nhập để xem chi tiết đơn hàng.");
        return;
      }
      void load();
    }, 0);
    return () => clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    const refresh = (event: Event) => {
      const changedOrderId = (event as CustomEvent<{ orderId?: string }>).detail?.orderId;
      if (!changedOrderId || changedOrderId === orderId) void load(false);
    };
    window.addEventListener(CUSTOMER_ORDER_CHANGED_EVENT, refresh);
    return () => {
      window.removeEventListener(CUSTOMER_ORDER_CHANGED_EVENT, refresh);
    };
  }, [load, orderId]);

  const handleCancel = async () => {
    if (!order) return;
    const paid = order.paymentStatus === "PAID";
    // Only an unpaid order still awaiting confirmation cancels immediately - stock is
    // just reserved there. Once CONFIRMED/PACKING (real stock deducted) or paid, it
    // always needs staff approval, regardless of payment method.
    const immediate = order.orderStatus === "PENDING_CONFIRMATION" && !paid;
    if (!window.confirm(immediate
      ? "Bạn chắc chắn muốn hủy đơn này?"
      : paid
        ? "Gửi yêu cầu hủy đơn và hoàn lại toàn bộ tiền qua VNPay?"
        : "Gửi yêu cầu hủy đơn? Cửa hàng sẽ xem xét và xác nhận.")) return;
    setCancelling(true);
    try {
      const updated = await cancelOrder(order.id, immediate ? undefined : "Khách hàng yêu cầu hủy đơn");
      setOrder(updated);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể hủy đơn hàng."));
    } finally {
      setCancelling(false);
    }
  };

  const handlePay = async () => {
    if (!order) return;
    setPaying(true);
    setError(null);
    try {
      const payment = await createVnpayPayment(order.id);
      sessionStorage.setItem("vnpay-pending-order-id", order.id);
      window.location.assign(payment.paymentUrl);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể mở cổng VNPay."));
      setPaying(false);
    }
  };

  const canCancel = order != null
    && ["PENDING_CONFIRMATION", "CONFIRMED", "PACKING"].includes(order.orderStatus);
  const canPay = order?.orderStatus === "PENDING_CONFIRMATION"
    && order.paymentMethod === "VNPAY" && order.paymentStatus !== "PAID";
  const canPrintInvoice = order != null && isInvoiceEligible(order);

  return (
    <main className="page-below-header">
      <section className="mx-auto w-full max-w-4xl px-4 py-10 md:px-6">
      <Link
        href="/tai-khoan"
        className="text-[13px] uppercase tracking-[0.08em] text-ivy-text hover:text-ivy-accent"
      >
        ← Về tài khoản
      </Link>

      {loading ? (
        <div className="mt-6 border border-ivy-hairline px-6 py-10 text-[15px] text-ivy-text">
          Đang tải chi tiết đơn hàng...
        </div>
      ) : error ? (
        <div className="mt-6 border border-ivy-hairline px-6 py-10 text-[15px] text-ivy-text">{error}</div>
      ) : order ? (
        <article className="mt-6 border border-ivy-hairline px-6 py-6">
          <div className="flex flex-col gap-4 border-b border-ivy-hairline pb-5 md:flex-row md:items-start md:justify-between">
            <div>
              <h1 className="text-[26px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">
                {order.orderCode}
              </h1>
              <p className="mt-2 text-[14px] text-ivy-text">
                Ngày tạo: {new Date(order.createdAt).toLocaleString("vi-VN")}
              </p>
              <p className="mt-1 text-[14px] text-ivy-text">Trạng thái đơn: {getOrderStatusLabel(order.orderStatus)}</p>
              <p className="mt-1 text-[14px] text-ivy-text">
                Thanh toán: {order.paymentMethod} / {order.paymentStatus}
              </p>
            </div>
            <div className="text-right">
              <p className="text-[13px] uppercase tracking-[0.08em] text-ivy-text-muted">Tổng thanh toán</p>
              <p className="mt-2 text-[24px] font-semibold text-ivy-dark">{money(order.totalAmount)}</p>
              <div className="mt-4 flex flex-wrap justify-end gap-3">
                {canPrintInvoice ? (
                  <InvoicePrintLink
                    order={order}
                    className="inline-flex h-10 items-center justify-center rounded-tl-[18px] rounded-br-[18px] border border-ivy-dark px-5 text-[12px] font-semibold uppercase tracking-[0.05em] text-ivy-dark"
                  />
                ) : null}
                {canCancel ? (
                  <button
                    type="button"
                    onClick={() => void handleCancel()}
                    disabled={cancelling}
                    className="inline-flex h-10 items-center justify-center rounded-tl-[18px] rounded-br-[18px] border border-ivy-dark px-5 text-[12px] font-semibold uppercase tracking-[0.05em] text-ivy-dark disabled:opacity-60"
                  >
                    {cancelling
                      ? "Đang xử lý..."
                      : order.orderStatus === "PENDING_CONFIRMATION" && order.paymentStatus !== "PAID"
                        ? "Hủy đơn"
                        : order.paymentStatus === "PAID"
                          ? "Hủy đơn & hoàn tiền"
                          : "Hủy đơn"}
                  </button>
                ) : null}
                {canPay ? (
                  <button
                    type="button"
                    onClick={() => void handlePay()}
                    disabled={paying}
                    className="inline-flex h-10 items-center justify-center rounded-tl-[18px] rounded-br-[18px] bg-ivy-dark px-5 text-[12px] font-semibold uppercase tracking-[0.05em] text-white disabled:opacity-60"
                  >
                    {paying ? "Đang mở VNPay..." : "Thanh toán VNPay"}
                  </button>
                ) : null}
              </div>
            </div>
          </div>

          <div className="mt-5 space-y-4">
            {order.items.map((item) => (
              <div
                key={item.id}
                className="flex gap-4 border-b border-dashed border-ivy-hairline pb-4 last:border-none last:pb-0 md:items-center"
              >
                <Link href={`/sanpham/${item.productId}`} aria-label={`Xem chi tiết ${item.productName}`} className="relative h-28 w-24 shrink-0 overflow-hidden bg-[#f5f5f5]">
                  {item.thumbnail ? <Image src={item.thumbnail} alt={item.productName} fill sizes="96px" className="object-cover" /> : null}
                </Link>
                <Link href={`/sanpham/${item.productId}`} className="min-w-0 flex-1 hover:text-ivy-accent">
                  <p className="text-[16px] font-medium text-ivy-dark">{item.productName}</p>
                  <p className="mt-1 text-[14px] text-ivy-text">SKU: {item.sku}</p>
                  <p className="mt-1 text-[14px] text-ivy-text">
                    Màu / Size: {item.color || "N/A"} / {item.size || "N/A"}
                  </p>
                  <p className="mt-1 text-[14px] text-ivy-text">
                    {money(item.unitPrice)} × {item.quantity}
                  </p>
                </Link>
                <div className="text-[18px] font-semibold text-ivy-dark">{money(item.lineTotal)}</div>
              </div>
            ))}
          </div>

          <dl className="mt-6 space-y-2 border-t border-ivy-hairline pt-5 text-[14px] text-ivy-text">
            <div className="flex justify-between">
              <dt>Tạm tính</dt>
              <dd>{money(order.subtotalAmount)}</dd>
            </div>
            <div className="flex justify-between">
              <dt>Phí vận chuyển</dt>
              <dd>{money(order.shippingFee)}</dd>
            </div>
            {order.discountAmount > 0 ? (
              <div className="flex justify-between">
                <dt>Giảm giá</dt>
                <dd>-{money(order.discountAmount)}</dd>
              </div>
            ) : null}
            <div className="flex justify-between pt-2 text-[16px] font-semibold text-ivy-dark">
              <dt>Tổng cộng</dt>
              <dd>{money(order.totalAmount)}</dd>
            </div>
          </dl>

          {order.note ? (
            <p className="mt-5 border-t border-ivy-hairline pt-4 text-[14px] text-ivy-text">
              Ghi chú: {order.note}
            </p>
          ) : null}
        </article>
      ) : null}
      </section>
    </main>
  );
}
