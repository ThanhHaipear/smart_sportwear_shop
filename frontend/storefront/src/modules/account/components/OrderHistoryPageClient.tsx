"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { getApiErrorMessage } from "@/lib/api-errors";
import { useAuthenticated } from "@/lib/use-authenticated";
import { cancelOrder, listMyOrders } from "@/modules/account/api";
import type { OrderResponse } from "@/modules/account/types";
import { getOrderStatusLabel, isInvoiceEligible } from "@/modules/account/order-labels";
import { CUSTOMER_ORDER_CHANGED_EVENT } from "@/modules/notifications/types";
import { InvoicePrintLink } from "@/modules/account/components/InvoicePrintLink";

const ORDERS_PER_PAGE = 5;

export function OrderHistoryPageClient() {
  const authenticated = useAuthenticated();
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [totalOrders, setTotalOrders] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [cancellingId, setCancellingId] = useState<string | null>(null);

  const loadOrders = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    setError(null);
    try {
      const result = await listMyOrders({ page, limit: ORDERS_PER_PAGE });
      setOrders(result.data);
      setTotalPages(Math.max(1, result.meta?.totalPages ?? 1));
      setTotalOrders(result.meta?.total ?? result.data.length);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể tải lịch sử đơn hàng."));
    } finally {
      if (showLoading) setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    if (!authenticated) {
      setLoading(false);
      return;
    }
    void loadOrders();
  }, [authenticated, loadOrders]);

  useEffect(() => {
    if (!authenticated) return;
    // Realtime updates replace the data in place. Keep the current list visible
    // so an admin status change does not flash the full-page loading state.
    const refresh = () => void loadOrders(false);
    window.addEventListener(CUSTOMER_ORDER_CHANGED_EVENT, refresh);
    return () => {
      window.removeEventListener(CUSTOMER_ORDER_CHANGED_EVENT, refresh);
    };
  }, [authenticated, loadOrders]);

  const handleCancel = async (id: string) => {
    const target = orders.find((order) => order.id === id);
    if (!target) return;
    const paid = target.paymentStatus === "PAID";
    if (!window.confirm(paid
      ? "Hủy đơn ngay? Giao dịch hoàn tiền VNPay sẽ được tạo để cửa hàng xử lý."
      : "Bạn chắc chắn muốn hủy đơn này?")) return;
    setCancellingId(id);
    setError(null);
    setSuccess(null);
    try {
      const updated = await cancelOrder(id, paid ? "Khách hàng yêu cầu hủy và hoàn tiền" : "Khách hàng yêu cầu hủy");
      await loadOrders();
      setSuccess(paid
        ? "Đơn hàng đã được hủy. Giao dịch hoàn tiền VNPay đang chờ cửa hàng xử lý."
        : "Đơn hàng đã được hủy.");
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể hủy đơn hàng."));
    } finally {
      setCancellingId(null);
    }
  };

  const canCancel = (order: OrderResponse) =>
    ["PENDING_CONFIRMATION", "CONFIRMED", "PACKING"].includes(order.orderStatus);
  const canPrintInvoice = isInvoiceEligible;

  if (!authenticated) {
    return (
      <main className="site-main page-below-header-spacious flex-1 border-b border-ivy-hairline">
        <div className="mx-auto max-w-[1380px] px-4 pb-24">
          <div className="border border-ivy-hairline px-6 py-10">
            <h1 className="text-[32px] font-semibold uppercase tracking-[0.06em] text-ivy-dark">Tra cứu đơn hàng</h1>
            <p className="mt-4 text-[15px] leading-7 text-ivy-text">Vui lòng đăng nhập tài khoản khách hàng để xem lịch sử đơn hàng thật từ hệ thống.</p>
            <Link
              href="/dang-nhap"
              className="mt-8 inline-flex h-12 items-center justify-center rounded-tl-[20px] rounded-br-[20px] bg-ivy-dark px-8 text-[14px] font-semibold uppercase tracking-[0.05em] text-white"
            >
              Đăng nhập
            </Link>
          </div>
        </div>
      </main>
    );
  }

  return (
    <main className="site-main page-below-header-spacious flex-1 border-b border-ivy-hairline">
      <div className="mx-auto max-w-[1380px] px-4 pb-24">
        <div className="mb-10">
          <p className="mb-3 text-[13px] uppercase tracking-[0.24em] text-ivy-text-muted">Khách hàng</p>
          <h1 className="text-[40px] font-semibold uppercase tracking-[0.06em] text-ivy-dark">Tra cứu đơn hàng</h1>
        </div>

        {error ? <p className="mb-4 text-[14px] text-[#C62127]">{error}</p> : null}
        {success ? <p className="mb-4 text-[14px] text-[#257A4D]">{success}</p> : null}

        {loading ? (
          <div className="border border-ivy-hairline px-6 py-10 text-[15px] text-ivy-text">Đang tải đơn hàng...</div>
        ) : orders.length > 0 ? (
          <div className="space-y-6">
            {orders.map((order) => (
              <article key={order.id} className="border border-ivy-hairline px-6 py-6">
                <div className="flex flex-col gap-4 border-b border-ivy-hairline pb-5 md:flex-row md:items-start md:justify-between">
                  <div>
                    <h2 className="text-[22px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">{order.orderCode}</h2>
                    <p className="mt-2 text-[14px] text-ivy-text">Ngày tạo: {new Date(order.createdAt).toLocaleString("vi-VN")}</p>
                    <p className="mt-1 text-[14px] text-ivy-text">Trạng thái đơn: {getOrderStatusLabel(order.orderStatus)}</p>
                    <p className="mt-1 text-[14px] text-ivy-text">Thanh toán: {order.paymentMethod} / {order.paymentStatus}</p>
                  </div>
                  <div className="text-right">
                    <p className="text-[13px] uppercase tracking-[0.08em] text-ivy-text-muted">Tổng thanh toán</p>
                    <p className="mt-2 text-[24px] font-semibold text-ivy-dark">{order.totalAmount.toLocaleString("vi-VN")}đ</p>
                    <div className="mt-4 flex flex-wrap justify-end gap-3">
                      {canPrintInvoice(order) ? (
                        <InvoicePrintLink
                          order={order}
                          className="inline-flex h-10 items-center justify-center rounded-tl-[18px] rounded-br-[18px] border border-ivy-dark px-5 text-[12px] font-semibold uppercase tracking-[0.05em] text-ivy-dark"
                        />
                      ) : null}
                      {canCancel(order) ? (
                        <button
                          type="button"
                          onClick={() => void handleCancel(order.id)}
                          disabled={cancellingId === order.id}
                          className="inline-flex h-10 items-center justify-center rounded-tl-[18px] rounded-br-[18px] border border-ivy-dark px-5 text-[12px] font-semibold uppercase tracking-[0.05em] text-ivy-dark disabled:opacity-60"
                        >
                          {cancellingId === order.id
                            ? "Đang xử lý..."
                            : order.orderStatus === "PENDING_CONFIRMATION" && order.paymentStatus !== "PAID"
                              ? "Hủy đơn"
                            : order.paymentStatus === "PAID"
                                ? "Hủy đơn & hoàn tiền"
                                : "Hủy đơn"}
                        </button>
                      ) : null}
                    </div>
                  </div>
                </div>

                <div className="mt-5 space-y-4">
                  {order.items.map((item) => (
                    <div key={item.id} className="flex gap-4 border-b border-dashed border-ivy-hairline pb-4 last:border-none last:pb-0 md:items-center">
                      <Link href={`/sanpham/${item.productId}`} aria-label={`Xem chi tiết ${item.productName}`} className="relative h-28 w-24 shrink-0 overflow-hidden bg-[#f5f5f5]">
                        {item.thumbnail ? <Image src={item.thumbnail} alt={item.productName} fill sizes="96px" className="object-cover" /> : null}
                      </Link>
                      <Link href={`/sanpham/${item.productId}`} className="min-w-0 flex-1 hover:text-ivy-accent">
                        <p className="text-[16px] font-medium text-ivy-dark">{item.productName}</p>
                        <p className="mt-1 text-[14px] text-ivy-text">SKU: {item.sku}</p>
                        <p className="mt-1 text-[14px] text-ivy-text">Màu / Size: {item.color || "N/A"} / {item.size || "N/A"}</p>
                        <p className="mt-1 text-[14px] text-ivy-text">Số lượng: {item.quantity}</p>
                      </Link>
                      <div className="text-[18px] font-semibold text-ivy-dark">{item.lineTotal.toLocaleString("vi-VN")}đ</div>
                    </div>
                  ))}
                </div>
              </article>
            ))}
            <nav className="flex flex-col items-center justify-between gap-4 pt-2 sm:flex-row" aria-label="Phân trang đơn hàng">
              <p className="text-[13px] text-ivy-text-muted">
                Hiển thị {(page - 1) * ORDERS_PER_PAGE + 1}–{Math.min(page * ORDERS_PER_PAGE, totalOrders)} trong {totalOrders} đơn hàng
              </p>
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => setPage((current) => Math.max(1, current - 1))}
                  disabled={page <= 1 || loading}
                  className="h-10 rounded-tl-[16px] rounded-br-[16px] border border-ivy-hairline px-4 text-[12px] font-semibold uppercase tracking-[0.05em] text-ivy-dark disabled:cursor-not-allowed disabled:opacity-40"
                >
                  Trang trước
                </button>
                <span className="min-w-20 text-center text-[14px] text-ivy-text">
                  Trang {page}/{totalPages}
                </span>
                <button
                  type="button"
                  onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
                  disabled={page >= totalPages || loading}
                  className="h-10 rounded-tl-[16px] rounded-br-[16px] border border-ivy-hairline px-4 text-[12px] font-semibold uppercase tracking-[0.05em] text-ivy-dark disabled:cursor-not-allowed disabled:opacity-40"
                >
                  Trang sau
                </button>
              </div>
            </nav>
          </div>
        ) : (
          <div className="border border-ivy-hairline px-6 py-10 text-[15px] text-ivy-text">Bạn chưa có đơn hàng nào.</div>
        )}
      </div>
    </main>
  );
}
