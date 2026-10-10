import type { AdminOrderResponse } from "@/modules/orders/types";

export const orderStatusLabels: Record<string, string> = {
  PENDING_CONFIRMATION: "Chờ xác nhận",
  CONFIRMED: "Đã xác nhận",
  PACKING: "Đang đóng gói",
  SHIPPING: "Đang giao",
  DELIVERED: "Đã giao",
  CANCELLED: "Đã hủy"
};

const paymentMethodLabels: Record<string, string> = {
  COD: "Thanh toán khi nhận hàng (COD)",
  VNPAY: "Chuyển khoản qua VNPay"
};

const paymentStatusLabels: Record<string, string> = {
  UNPAID: "Chưa thanh toán",
  PENDING: "Đang chờ thanh toán",
  PAID: "Đã thanh toán",
  FAILED: "Thanh toán thất bại",
  CANCELLED: "Đã hủy thanh toán",
  REFUNDED: "Đã hoàn tiền"
};

function formatCurrency(value: number) {
  return `${Math.round(value).toLocaleString("vi-VN")}₫`;
}

export const PACKING_SLIP_STYLES = `
  .packing-slip { max-width: 760px; margin: 0 auto; padding: 32px 24px; font-family: "Segoe UI", Arial, sans-serif; color: #1a1a1a; background: #fff; }
  .packing-slip-status { padding: 48px; text-align: center; font-family: "Segoe UI", Arial, sans-serif; }
  .ps-toolbar { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-bottom: 20px; }
  .ps-toolbar button { padding: 8px 16px; border-radius: 6px; border: 1px solid #ccc; background: #f5f5f5; cursor: pointer; font-size: 14px; }
  .ps-toolbar-message { font-size: 14px; color: #257A4D; }
  .ps-header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #1a1a1a; padding-bottom: 16px; margin-bottom: 20px; }
  .ps-shop { font-size: 20px; font-weight: 800; }
  .ps-title { text-align: right; }
  .ps-title h1 { font-size: 22px; margin: 0; }
  .ps-title div { font-size: 14px; color: #555; margin-top: 4px; }
  .ps-section { margin-bottom: 20px; }
  .ps-section h2 { font-size: 13px; text-transform: uppercase; letter-spacing: 0.05em; color: #555; margin: 0 0 8px; }
  .ps-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
  .ps-box { border: 1px solid #ddd; border-radius: 8px; padding: 14px 16px; }
  .ps-box p { margin: 2px 0; font-size: 14px; }
  .ps-box .name { font-weight: 700; font-size: 15px; }
  table.ps-items { width: 100%; border-collapse: collapse; margin-top: 8px; }
  table.ps-items th, table.ps-items td { border: 1px solid #ddd; padding: 8px 10px; font-size: 13px; text-align: left; }
  table.ps-items th { background: #f5f5f5; }
  table.ps-items td.num, table.ps-items th.num { text-align: right; }
  .ps-totals { margin-top: 12px; width: 100%; max-width: 320px; margin-left: auto; font-size: 14px; }
  .ps-totals div { display: flex; justify-content: space-between; padding: 3px 0; }
  .ps-totals .grand { font-weight: 800; font-size: 16px; border-top: 1px solid #1a1a1a; margin-top: 6px; padding-top: 6px; }
  .ps-cod { margin-top: 10px; padding: 12px 16px; border: 2px solid #1a1a1a; border-radius: 8px; text-align: center; font-weight: 800; font-size: 16px; }
  .ps-signatures { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 48px; text-align: center; font-size: 14px; }
  .ps-signatures .line { margin-top: 64px; border-top: 1px solid #999; padding-top: 6px; }
  .ps-page-break { page-break-before: always; break-before: page; margin-top: 40px; border-top: 2px dashed #ccc; padding-top: 24px; }
  @media print {
    .no-print { display: none !important; }
    .packing-slip { padding: 0; max-width: none; }
    .ps-page-break { margin-top: 0; border-top: none; padding-top: 0; }
    @page { margin: 14mm; }
  }
`;

export function PackingSlipContent({ order, pageBreakBefore }: { order: AdminOrderResponse; pageBreakBefore?: boolean }) {
  const address = order.shippingAddress;
  const codAmount = order.paymentMethod === "COD" && order.paymentStatus !== "PAID" ? order.totalAmount : null;

  return (
    <div className={`packing-slip${pageBreakBefore ? " ps-page-break" : ""}`}>
      <div className="ps-header">
        <div className="ps-shop">Thanh Hùng Futsal</div>
        <div className="ps-title">
          <h1>PHIẾU ĐÓNG GÓI / GIAO HÀNG</h1>
          <div>Mã đơn: <strong>{order.orderCode}</strong></div>
          <div>Ngày đặt: {new Date(order.createdAt).toLocaleString("vi-VN")}</div>
        </div>
      </div>

      <div className="ps-grid ps-section">
        <div className="ps-box">
          <h2>Người nhận</h2>
          <p className="name">{address?.receiverName ?? order.customerName}</p>
          <p>{address?.phone ?? order.customerPhone ?? "Chưa có số điện thoại"}</p>
          <p>{[address?.addressLine, address?.ward, address?.district, address?.province].filter(Boolean).join(", ") || "Chưa có địa chỉ giao hàng"}</p>
        </div>
        <div className="ps-box">
          <h2>Thông tin đơn hàng</h2>
          <p>Trạng thái: {orderStatusLabels[order.orderStatus] ?? order.orderStatus}</p>
          <p>Thanh toán: {paymentMethodLabels[order.paymentMethod] ?? order.paymentMethod}</p>
          <p>Tình trạng thanh toán: {paymentStatusLabels[order.paymentStatus] ?? order.paymentStatus}</p>
          {order.note ? <p>Ghi chú của khách: {order.note}</p> : null}
        </div>
      </div>

      <div className="ps-section">
        <h2>Sản phẩm</h2>
        <table className="ps-items">
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
                <td>{[item.size, item.color].filter(Boolean).join(" / ") || "-"}</td>
                <td className="num">{item.quantity}</td>
                <td className="num">{formatCurrency(item.unitPrice)}</td>
                <td className="num">{formatCurrency(item.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div className="ps-totals">
          <div><span>Tạm tính</span><span>{formatCurrency(order.subtotalAmount)}</span></div>
          <div><span>Phí vận chuyển</span><span>{formatCurrency(order.shippingFee)}</span></div>
          {order.discountAmount > 0 ? <div><span>Giảm giá</span><span>-{formatCurrency(order.discountAmount)}</span></div> : null}
          <div className="grand"><span>Tổng cộng</span><span>{formatCurrency(order.totalAmount)}</span></div>
        </div>

        {codAmount !== null ? <div className="ps-cod">Thu hộ (COD): {formatCurrency(codAmount)}</div> : null}
      </div>

      <div className="ps-signatures">
        <div>
          <div>Người giao hàng</div>
          <div className="line">Ký, ghi rõ họ tên</div>
        </div>
        <div>
          <div>Người nhận hàng</div>
          <div className="line">Ký, ghi rõ họ tên</div>
        </div>
      </div>
    </div>
  );
}
