"use client";

import { useCallback, useEffect, useState } from "react";
import { getOrderInvoice } from "@/modules/account/api";
import type { OrderResponse } from "@/modules/account/types";
import { ApiError } from "@/lib/api";
import { getApiErrorMessage } from "@/lib/api-errors";
import { getAccessToken } from "@/lib/session";
import { getOrderStatusLabel } from "@/modules/account/order-labels";
import { readTransferredInvoice } from "@/modules/account/components/InvoicePrintLink";

const money = (n: number) => `${Math.round(n).toLocaleString("vi-VN")}đ`;

const paymentMethodLabels: Record<string, string> = {
  COD: "Thanh toán khi nhận hàng (COD)",
  VNPAY: "Chuyển khoản qua VNPay",
  VIETQR: "Chuyển khoản VietQR",
};

const paymentStatusLabels: Record<string, string> = {
  UNPAID: "Chưa thanh toán",
  PENDING: "Đang chờ thanh toán",
  PAID: "Đã thanh toán",
  FAILED: "Thanh toán thất bại",
  CANCELLED: "Đã hủy thanh toán",
  REFUNDED: "Đã hoàn tiền"
};

const NOT_ELIGIBLE_MESSAGE =
  "Đơn hàng chưa đủ điều kiện xuất hóa đơn — cần đã thanh toán thành công và không trong quá trình hủy.";

export function OrderInvoiceClient({ orderId, payloadKey }: { orderId: string; payloadKey?: string }) {
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setOrder(await getOrderInvoice(orderId));
    } catch (err) {
      // The eligibility rule (paid, not cancelled) is enforced server-side -
      // show a fixed, user-facing explanation for a 409 rather than whatever
      // raw message the backend happens to send.
      setOrder(null);
      setError(err instanceof ApiError && err.status === 409
        ? NOT_ELIGIBLE_MESSAGE
        : getApiErrorMessage(err, "Không thể tải hóa đơn."));
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    const transferredOrder = readTransferredInvoice(payloadKey, orderId);
    if (transferredOrder) {
      setOrder(transferredOrder);
      setLoading(false);
    }
    const timer = setTimeout(() => {
      if (!getAccessToken()) {
        setLoading(false);
        setError("Vui lòng đăng nhập để xem hóa đơn.");
        return;
      }
      void load();
    }, 0);
    return () => clearTimeout(timer);
  }, [load, orderId, payloadKey]);

  if (loading) {
    return <div className="invoice-status">Đang tải hóa đơn...</div>;
  }
  if (error || !order) {
    return <div className="invoice-status">{error ?? "Không tìm thấy đơn hàng."}</div>;
  }

  const address = order.shippingAddress;

  return (
    <div className="invoice">
      <style>{`
        .invoice { max-width: 760px; margin: 0 auto; padding: 32px 24px; font-family: "Segoe UI", Arial, sans-serif; color: #1a1a1a; background: #fff; }
        .invoice-status { padding: 48px; text-align: center; font-family: "Segoe UI", Arial, sans-serif; }
        .inv-toolbar { display: flex; justify-content: flex-end; gap: 8px; margin-bottom: 20px; }
        .inv-toolbar button { padding: 8px 16px; border-radius: 6px; border: 1px solid #ccc; background: #f5f5f5; cursor: pointer; font-size: 14px; }
        .inv-header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #1a1a1a; padding-bottom: 16px; margin-bottom: 20px; }
        .inv-shop { font-size: 20px; font-weight: 800; }
        .inv-title { text-align: right; }
        .inv-title h1 { font-size: 22px; margin: 0; }
        .inv-title div { font-size: 14px; color: #555; margin-top: 4px; }
        .inv-number { font-size: 14px; color: #1a1a1a; font-weight: 700; margin-top: 4px; }
        .inv-section { margin-bottom: 20px; }
        .inv-section h2 { font-size: 13px; text-transform: uppercase; letter-spacing: 0.05em; color: #555; margin: 0 0 8px; }
        .inv-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
        .inv-grid.inv-grid-3 { grid-template-columns: 1fr 1fr 1fr; }
        .inv-box { border: 1px solid #ddd; border-radius: 8px; padding: 14px 16px; }
        .inv-box p { margin: 2px 0; font-size: 14px; }
        .inv-box .name { font-weight: 700; font-size: 15px; }
        table.inv-items { width: 100%; border-collapse: collapse; margin-top: 8px; }
        table.inv-items th, table.inv-items td { border: 1px solid #ddd; padding: 8px 10px; font-size: 13px; text-align: left; }
        table.inv-items th { background: #f5f5f5; }
        table.inv-items td.num, table.inv-items th.num { text-align: right; }
        .inv-totals { margin-top: 12px; width: 100%; max-width: 320px; margin-left: auto; font-size: 14px; }
        .inv-totals div { display: flex; justify-content: space-between; padding: 3px 0; }
        .inv-totals .grand { font-weight: 800; font-size: 16px; border-top: 1px solid #1a1a1a; margin-top: 6px; padding-top: 6px; }
        .inv-footer { margin-top: 40px; text-align: center; font-size: 13px; color: #777; }
        @media print {
          .no-print { display: none !important; }
          .invoice { padding: 0; max-width: none; }
          @page { margin: 14mm; }
        }
      `}</style>

      <div className="inv-toolbar no-print">
        <button type="button" onClick={() => window.print()}>In hóa đơn</button>
      </div>

      <div className="inv-header">
        <div className="inv-shop">Điểm Đến Thể Thao</div>
        <div className="inv-title">
          <h1>HÓA ĐƠN BÁN HÀNG</h1>
          {order.invoiceNumber ? <div className="inv-number">Số: {order.invoiceNumber}</div> : null}
          <div>Mã đơn: <strong>{order.orderCode}</strong></div>
          <div>Ngày đặt: {new Date(order.createdAt).toLocaleString("vi-VN")}</div>
        </div>
      </div>

      <div className={`inv-grid inv-section${order.invoiceRequested ? " inv-grid-3" : ""}`}>
        <div className="inv-box">
          <h2>Địa chỉ giao hàng</h2>
          <p className="name">{address?.receiverName ?? "-"}</p>
          <p>{address?.phone ?? "-"}</p>
          <p>{[address?.addressLine, address?.ward, address?.district, address?.province].filter(Boolean).join(", ") || "Chưa có địa chỉ"}</p>
        </div>
        {order.invoiceRequested ? (
          <div className="inv-box">
            <h2>Đơn vị mua hàng</h2>
            <p className="name">{order.invoiceCompanyName}</p>
            <p>MST: {order.invoiceTaxCode}</p>
            <p>{order.invoiceCompanyAddress}</p>
          </div>
        ) : null}
        <div className="inv-box">
          <h2>Thông tin đơn hàng</h2>
          <p>Trạng thái: {getOrderStatusLabel(order.orderStatus)}</p>
          <p>Thanh toán: {paymentMethodLabels[order.paymentMethod] ?? order.paymentMethod}</p>
          <p>Tình trạng thanh toán: {paymentStatusLabels[order.paymentStatus] ?? order.paymentStatus}</p>
        </div>
      </div>

      <div className="inv-section">
        <h2>Sản phẩm</h2>
        <table className="inv-items">
          <thead>
            <tr>
              <th>#</th>
              <th>Sản phẩm</th>
              <th>SKU</th>
              <th>Phân loại</th>
              <th className="num">SL</th>
              <th className="num">Đơn giá</th>
              <th className="num">Thành tiền</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((item, index) => (
              <tr key={item.id}>
                <td>{index + 1}</td>
                <td>{item.productName}</td>
                <td>{item.sku}</td>
                <td>{[item.color, item.size].filter(Boolean).join(" / ") || "-"}</td>
                <td className="num">{item.quantity}</td>
                <td className="num">{money(item.unitPrice)}</td>
                <td className="num">{money(item.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div className="inv-totals">
          <div><span>Tạm tính</span><span>{money(order.subtotalAmount)}</span></div>
          <div><span>Phí vận chuyển</span><span>{money(order.shippingFee)}</span></div>
          {order.discountAmount > 0 ? <div><span>Giảm giá</span><span>-{money(order.discountAmount)}</span></div> : null}
          <div className="grand"><span>Tổng cộng</span><span>{money(order.totalAmount)}</span></div>
        </div>
      </div>

      <div className="inv-footer">Cảm ơn bạn đã mua sắm tại Điểm Đến Thể Thao.</div>
    </div>
  );
}
