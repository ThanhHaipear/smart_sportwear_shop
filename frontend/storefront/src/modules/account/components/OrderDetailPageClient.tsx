"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { cancelOrder, getOrderDetail, getOrderRefunds, updateRefundDestination } from "@/modules/account/api";
import type { OrderResponse, RefundResponse } from "@/modules/account/types";
import { getApiErrorMessage } from "@/lib/api-errors";
import { getAccessToken } from "@/lib/session";
import { createVietQrPayment } from "@/modules/checkout/api";
import { getOrderStatusLabel, isInvoiceEligible } from "@/modules/account/order-labels";
import { CUSTOMER_ORDER_CHANGED_EVENT } from "@/modules/notifications/types";
import { InvoicePrintLink } from "@/modules/account/components/InvoicePrintLink";

const money = (n: number) => `${n.toLocaleString("vi-VN")}đ`;

const REFUND_BANKS = [
  ["MB", "MB Bank"],
  ["VCB", "Vietcombank"],
  ["BIDV", "BIDV"],
  ["CTG", "VietinBank"],
  ["TCB", "Techcombank"],
  ["ACB", "ACB"],
  ["VPB", "VPBank"],
  ["TPB", "TPBank"],
  ["STB", "Sacombank"],
  ["OCB", "OCB"],
  ["MSB", "MSB"],
] as const;

const refundStatusLabels: Record<string, string> = {
  PENDING: "Đang chờ cửa hàng xử lý",
  PROCESSING: "Đang hoàn tiền",
  COMPLETED: "Đã hoàn tiền",
  FAILED: "Hoàn tiền thất bại",
  CANCELLED: "Yêu cầu hoàn tiền đã hủy",
};

export function OrderDetailPageClient({ orderId }: { orderId: string }) {
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [refunds, setRefunds] = useState<RefundResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [paying, setPaying] = useState(false);
  const [showRefundForm, setShowRefundForm] = useState(false);
  const [refundForm, setRefundForm] = useState({
    reason: "Khách hàng yêu cầu hủy đơn và hoàn tiền",
    bankCode: "MB",
    bankName: "MB Bank",
    accountNumber: "",
    accountName: "",
  });

  const load = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    setError(null);
    try {
      const [nextOrder, nextRefunds] = await Promise.all([
        getOrderDetail(orderId),
        getOrderRefunds(orderId),
      ]);
      setOrder(nextOrder);
      setRefunds(nextRefunds);
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
    if (paid && order.paymentMethod === "VIETQR") {
      setShowRefundForm(true);
      return;
    }
    if (!window.confirm(immediate
      ? "Bạn chắc chắn muốn hủy đơn này?"
      : paid
        ? "Gửi yêu cầu hủy đơn và hoàn lại toàn bộ tiền?"
        : "Gửi yêu cầu hủy đơn? Cửa hàng sẽ xem xét và xác nhận.")) return;
    setCancelling(true);
    try {
      const updated = await cancelOrder(order.id, {
        reason: immediate ? undefined : "Khách hàng yêu cầu hủy đơn",
      });
      setOrder(updated);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể hủy đơn hàng."));
    } finally {
      setCancelling(false);
    }
  };

  const submitRefundDestination = async () => {
    if (!order) return;
    const accountNumber = refundForm.accountNumber.replace(/\s+/g, "");
    if (!/^[0-9]{6,30}$/.test(accountNumber)) {
      setError("Số tài khoản phải gồm từ 6 đến 30 chữ số.");
      return;
    }
    if (refundForm.accountName.trim().length < 2) {
      setError("Vui lòng nhập tên chủ tài khoản nhận hoàn.");
      return;
    }
    setCancelling(true);
    setError(null);
    try {
      const destination = {
        bankCode: refundForm.bankCode,
        bankName: refundForm.bankName,
        accountNumber,
        accountName: refundForm.accountName.trim(),
      };
      if (order.orderStatus !== "CANCELLED") {
        await cancelOrder(order.id, { reason: refundForm.reason.trim(), ...destination });
      } else {
        await updateRefundDestination(order.id, destination);
      }
      setShowRefundForm(false);
      await load(false);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể gửi thông tin hoàn tiền."));
    } finally {
      setCancelling(false);
    }
  };

  const handlePay = async () => {
    if (!order) return;
    setPaying(true);
    setError(null);
    try {
      const payment = await createVietQrPayment(order.id);
      window.location.assign(`/payment/vietqr/${payment.paymentId}`);
    } catch (err) {
      setError(getApiErrorMessage(err, "Không thể tạo mã thanh toán VietQR."));
      setPaying(false);
    }
  };

  const canCancel = order != null
    && ["PENDING_CONFIRMATION", "CONFIRMED", "PACKING"].includes(order.orderStatus);
  const canPay = order?.orderStatus === "PENDING_CONFIRMATION"
    && order.paymentMethod === "VIETQR" && order.paymentStatus !== "PAID";
  const canPrintInvoice = order != null && isInvoiceEligible(order);
  const activeRefund = refunds.find((refund) => ["PENDING", "PROCESSING"].includes(refund.status))
    ?? refunds[0];

  const editRefundDestination = () => {
    if (activeRefund?.refundAccountNumber) {
      setRefundForm({
        reason: activeRefund.reason ?? "Khách hàng yêu cầu hủy đơn và hoàn tiền",
        bankCode: activeRefund.refundBankCode ?? "MB",
        bankName: activeRefund.refundBankName ?? "MB Bank",
        accountNumber: activeRefund.refundAccountNumber,
        accountName: activeRefund.refundAccountName ?? "",
      });
    }
    setShowRefundForm(true);
  };

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
      ) : error && !order ? (
        <div className="mt-6 border border-ivy-hairline px-6 py-10 text-[15px] text-ivy-text">{error}</div>
      ) : order ? (
        <article className="mt-6 border border-ivy-hairline px-6 py-6">
          {error ? <p className="mb-5 border border-[#E9B4B4] bg-[#FFF7F7] px-4 py-3 text-[14px] text-[#C62127]">{error}</p> : null}
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
                    {paying ? "Đang tạo VietQR..." : "Thanh toán VietQR"}
                  </button>
                ) : null}
              </div>
            </div>
          </div>

          {activeRefund ? (
            <section className="mt-6 border border-[#D8C49A] bg-[#FFFBEF] p-5 text-left">
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h2 className="text-[17px] font-semibold text-ivy-dark">Yêu cầu hoàn tiền {activeRefund.refundCode}</h2>
                  <p className="mt-2 text-[14px] text-ivy-text">
                    Trạng thái: <strong>{refundStatusLabels[activeRefund.status] ?? activeRefund.status}</strong>
                  </p>
                  <p className="mt-1 text-[14px] text-ivy-text">Số tiền: <strong>{money(activeRefund.amount)}</strong></p>
                  {activeRefund.refundAccountNumber ? (
                    <p className="mt-1 text-[14px] text-ivy-text">
                      Nhận tại {activeRefund.refundBankName} · ••••{activeRefund.refundAccountNumber.slice(-4)} · {activeRefund.refundAccountName}
                    </p>
                  ) : (
                    <p className="mt-1 text-[14px] text-[#A16207]">Chưa có tài khoản nhận hoàn.</p>
                  )}
                  {activeRefund.manualReference ? (
                    <p className="mt-1 text-[14px] text-ivy-text">Mã giao dịch hoàn: {activeRefund.manualReference}</p>
                  ) : null}
                </div>
                {activeRefund.status === "PENDING" && order.paymentMethod === "VIETQR" ? (
                  <button
                    type="button"
                    onClick={editRefundDestination}
                    className="border border-ivy-dark px-4 py-2 text-[12px] font-semibold uppercase tracking-[0.04em]"
                  >
                    {activeRefund.refundAccountNumber ? "Sửa tài khoản" : "Thêm tài khoản"}
                  </button>
                ) : null}
              </div>
            </section>
          ) : null}

          {showRefundForm ? (
            <section className="mt-6 border border-ivy-hairline bg-white p-5 text-left">
              <h2 className="text-[19px] font-semibold text-ivy-dark">Thông tin nhận hoàn tiền</h2>
              <p className="mt-2 text-[14px] leading-6 text-ivy-text">
                Cửa hàng sẽ hoàn <strong>{money(order.totalAmount)}</strong>. Không cung cấp mật khẩu, mã OTP hoặc mã PIN ngân hàng.
              </p>
              <div className="mt-5 grid gap-4 md:grid-cols-2">
                <label className="text-[13px] text-ivy-text">
                  Ngân hàng
                  <select
                    value={refundForm.bankCode}
                    onChange={(event) => {
                      const selected = REFUND_BANKS.find(([code]) => code === event.target.value) ?? REFUND_BANKS[0];
                      setRefundForm((current) => ({ ...current, bankCode: selected[0], bankName: selected[1] }));
                    }}
                    className="mt-2 h-11 w-full border border-ivy-hairline bg-white px-3"
                  >
                    {REFUND_BANKS.map(([code, name]) => <option value={code} key={code}>{name}</option>)}
                  </select>
                </label>
                <label className="text-[13px] text-ivy-text">
                  Số tài khoản
                  <input
                    inputMode="numeric"
                    autoComplete="off"
                    value={refundForm.accountNumber}
                    onChange={(event) => setRefundForm((current) => ({ ...current, accountNumber: event.target.value.replace(/\D/g, "") }))}
                    className="mt-2 h-11 w-full border border-ivy-hairline px-3"
                    maxLength={30}
                  />
                </label>
                <label className="text-[13px] text-ivy-text md:col-span-2">
                  Tên chủ tài khoản
                  <input
                    value={refundForm.accountName}
                    onChange={(event) => setRefundForm((current) => ({ ...current, accountName: event.target.value.toUpperCase() }))}
                    className="mt-2 h-11 w-full border border-ivy-hairline px-3 uppercase"
                    maxLength={100}
                  />
                </label>
                {order.orderStatus !== "CANCELLED" ? (
                  <label className="text-[13px] text-ivy-text md:col-span-2">
                    Lý do hủy
                    <textarea
                      value={refundForm.reason}
                      onChange={(event) => setRefundForm((current) => ({ ...current, reason: event.target.value }))}
                      className="mt-2 min-h-24 w-full border border-ivy-hairline p-3"
                      maxLength={500}
                    />
                  </label>
                ) : null}
              </div>
              <div className="mt-5 flex flex-wrap gap-3">
                <button
                  type="button"
                  onClick={() => void submitRefundDestination()}
                  disabled={cancelling}
                  className="bg-ivy-dark px-6 py-3 text-[13px] font-semibold text-white disabled:opacity-60"
                >
                  {cancelling ? "Đang gửi..." : order.orderStatus === "CANCELLED" ? "Lưu tài khoản" : "Gửi yêu cầu"}
                </button>
                <button type="button" onClick={() => setShowRefundForm(false)} className="border border-ivy-dark px-6 py-3 text-[13px]">
                  Đóng
                </button>
              </div>
            </section>
          ) : null}

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
