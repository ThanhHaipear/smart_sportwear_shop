"use client";

import { FormEvent, useState } from "react";
import { RevenueChart } from "@/components/ui/AdminCharts";
import { fetchCustomerReport, fetchProductReport, fetchReports } from "@/modules/reports/browser-api";
import { listAdminOrdersPage } from "@/modules/orders/browser-api";
import type { AdminOrderResponse } from "@/modules/orders/types";
import type {
  CustomerReportResponse,
  OverviewReportResponse,
  ProductReportResponse,
  ReportFilters,
  RevenueGranularity,
  RevenueReportResponse
} from "@/modules/reports/api";

type Props = {
  overview: OverviewReportResponse;
  initialRevenue: RevenueReportResponse;
  initialCustomers: CustomerReportResponse;
  initialProducts: ProductReportResponse;
};

const GRANULARITIES: Array<{ value: RevenueGranularity; label: string }> = [
  { value: "DAY", label: "Theo ngày" },
  { value: "MONTH", label: "Theo tháng" },
  { value: "YEAR", label: "Theo năm" }
];

const EMPTY_META = { page: 1, limit: 10, total: 0, totalPages: 0 };

function money(value: number) {
  return `${Math.round(value).toLocaleString("vi-VN")} ₫`;
}

export function ReportsWorkspace({ overview, initialRevenue, initialCustomers, initialProducts }: Props) {
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [granularity, setGranularity] = useState<RevenueGranularity>(initialRevenue.granularity);
  const [revenue, setRevenue] = useState(initialRevenue);
  const [customers, setCustomers] = useState(initialCustomers);
  const [products, setProducts] = useState(initialProducts);
  const [loading, setLoading] = useState(false);
  const [orderLoading, setOrderLoading] = useState(false);
  const [orderSearch, setOrderSearch] = useState("");
  const [selectedCustomer, setSelectedCustomer] = useState<{ id: string; name: string } | null>(null);
  const [customerOrders, setCustomerOrders] = useState<AdminOrderResponse[]>([]);
  const [orderResultTotal, setOrderResultTotal] = useState(0);
  const [orderPage, setOrderPage] = useState(1);
  const [orderTotalPages, setOrderTotalPages] = useState(0);
  const [productOrderSearch, setProductOrderSearch] = useState("");
  const [selectedProduct, setSelectedProduct] = useState<{ id: string; name: string } | null>(null);
  const [productOrders, setProductOrders] = useState<AdminOrderResponse[]>([]);
  const [productOrderTotal, setProductOrderTotal] = useState(0);
  const [productOrderPage, setProductOrderPage] = useState(1);
  const [productOrderTotalPages, setProductOrderTotalPages] = useState(0);
  const [error, setError] = useState<string | null>(null);

  async function load(nextGranularity = granularity, filters: ReportFilters = { dateFrom, dateTo }) {
    setLoading(true);
    setError(null);
    try {
      const [nextRevenue, nextCustomers, nextProducts] = await fetchReports(filters, nextGranularity);
      setRevenue(nextRevenue);
      setCustomers(nextCustomers);
      setProducts(nextProducts);
    } catch {
      setError("Không tải được dữ liệu báo cáo. Vui lòng thử lại.");
    } finally {
      setLoading(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void load();
  }

  function changeGranularity(next: RevenueGranularity) {
    setGranularity(next);
    void load(next);
  }

  function clearDates() {
    setDateFrom("");
    setDateTo("");
    void load(granularity, {});
  }

  async function loadCustomerPage(page: number) {
    setLoading(true);
    setError(null);
    try {
      setCustomers(await fetchCustomerReport({ dateFrom, dateTo }, page));
    } catch {
      setError("Không tải được trang khách hàng.");
    } finally {
      setLoading(false);
    }
  }

  async function loadProductPage(page: number) {
    setLoading(true);
    setError(null);
    try {
      setProducts(await fetchProductReport({ dateFrom, dateTo }, page));
    } catch {
      setError("Không tải được trang mặt hàng.");
    } finally {
      setLoading(false);
    }
  }

  async function showCustomerOrders(customerId: string, customerName: string) {
    setSelectedCustomer({ id: customerId, name: customerName });
    setOrderSearch("");
    setOrderLoading(true);
    setError(null);
    try {
      const result = await listAdminOrdersPage(1, 20, undefined, undefined, customerId, dateFrom || undefined, dateTo || undefined);
      setCustomerOrders(result.items);
      setOrderResultTotal(result.meta.total);
      setOrderPage(result.meta.page);
      setOrderTotalPages(result.meta.totalPages);
    } catch {
      setCustomerOrders([]);
      setError("Không tải được đơn hàng của khách hàng.");
    } finally {
      setOrderLoading(false);
    }
  }

  async function searchOrder(event: FormEvent) {
    event.preventDefault();
    const keyword = orderSearch.trim();
    if (!keyword) return;
    setSelectedCustomer(null);
    setOrderLoading(true);
    setError(null);
    try {
      const result = await listAdminOrdersPage(1, 20, keyword, undefined, undefined, dateFrom || undefined, dateTo || undefined);
      setCustomerOrders(result.items);
      setOrderResultTotal(result.meta.total);
      setOrderPage(result.meta.page);
      setOrderTotalPages(result.meta.totalPages);
    } catch {
      setCustomerOrders([]);
      setError("Không tìm được thông tin đơn hàng. Vui lòng thử lại.");
    } finally {
      setOrderLoading(false);
    }
  }

  async function loadOrderPage(page: number) {
    setOrderLoading(true);
    setError(null);
    try {
      const result = await listAdminOrdersPage(page, 20, selectedCustomer ? undefined : orderSearch.trim(), undefined, selectedCustomer?.id, dateFrom || undefined, dateTo || undefined);
      setCustomerOrders(result.items);
      setOrderResultTotal(result.meta.total);
      setOrderPage(result.meta.page);
      setOrderTotalPages(result.meta.totalPages);
    } catch {
      setError("Không tải được trang đơn hàng.");
    } finally {
      setOrderLoading(false);
    }
  }

  async function showProductOrders(productId: string, productName: string) {
    setSelectedProduct({ id: productId, name: productName });
    setProductOrderSearch("");
    await loadProductOrderPage(1, productId, "");
  }

  async function searchProductOrder(event: FormEvent) {
    event.preventDefault();
    const keyword = productOrderSearch.trim();
    if (!keyword) return;
    setSelectedProduct(null);
    await loadProductOrderPage(1, undefined, keyword);
  }

  async function loadProductOrderPage(page: number, productId = selectedProduct?.id, keyword = productOrderSearch.trim()) {
    setOrderLoading(true);
    setError(null);
    try {
      const result = await listAdminOrdersPage(page, 20, productId ? undefined : keyword, undefined, undefined, dateFrom || undefined, dateTo || undefined, productId);
      setProductOrders(result.items);
      setProductOrderTotal(result.meta.total);
      setProductOrderPage(result.meta.page);
      setProductOrderTotalPages(result.meta.totalPages);
    } catch {
      setProductOrders([]);
      setError("Không tải được đơn hàng liên quan đến mặt hàng.");
    } finally {
      setOrderLoading(false);
    }
  }

  function closeProductOrders() {
    setSelectedProduct(null);
    setProductOrderSearch("");
    setProductOrders([]);
    setProductOrderTotal(0);
  }

  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>Báo cáo bán hàng</h1>
          <p>Thống kê doanh thu theo thời gian, khách hàng và mặt hàng từ dữ liệu đơn hàng thực tế.</p>
        </div>
      </section>

      <section className="kpi-grid">
        <article className="card kpi-card"><div className="kpi-label"><span>Doanh thu gộp</span></div><div className="kpi-value">{money(overview.grossRevenue)}</div></article>
        <article className="card kpi-card"><div className="kpi-label"><span>Doanh thu thực nhận</span></div><div className="kpi-value">{money(overview.realizedRevenue)}</div></article>
        <article className="card kpi-card"><div className="kpi-label"><span>Tổng đơn</span></div><div className="kpi-value">{overview.totalOrders.toLocaleString("vi-VN")}</div></article>
        <article className="card kpi-card"><div className="kpi-label"><span>Đơn chờ xử lý</span></div><div className="kpi-value">{overview.pendingOrders.toLocaleString("vi-VN")}</div></article>
      </section>

      <section className="card panel">
        <form className="report-filter-form" onSubmit={submit}>
          <label>Từ ngày<input type="date" value={dateFrom} max={dateTo || undefined} onChange={(e) => setDateFrom(e.target.value)} /></label>
          <label>Đến ngày<input type="date" value={dateTo} min={dateFrom || undefined} onChange={(e) => setDateTo(e.target.value)} /></label>
          <button className="filter-chip active" type="submit" disabled={loading}>{loading ? "Đang tải..." : "Áp dụng"}</button>
          <button className="filter-chip" type="button" disabled={loading} onClick={clearDates}>Xóa ngày</button>
        </form>
        {error && <p className="action-message report-error">{error}</p>}
      </section>

      <section className="card panel">
        <div className="panel-header">
          <div><h2>Bán hàng theo thời gian</h2><p className="panel-copy">{revenue.dateFrom || "Tất cả"} → {revenue.dateTo || "Hiện tại"}</p></div>
          <div className="filters">
            {GRANULARITIES.map((item) => <button key={item.value} type="button" disabled={loading} className={`filter-chip${granularity === item.value ? " active" : ""}`} onClick={() => changeGranularity(item.value)}>{item.label}</button>)}
          </div>
        </div>
        {revenue.points.length ? <RevenueChart data={revenue.points} /> : <p className="report-empty">Không có dữ liệu doanh thu trong khoảng đã chọn.</p>}
      </section>

      <section className="card panel">
        <div className="panel-header"><div><h2>Bán hàng theo khách hàng</h2><p className="panel-copy">Xếp hạng theo tổng giá trị đơn không bị hủy. Nhấn vào khách hàng để xem các đơn đã mua.</p></div></div>
        <form className="report-order-search" onSubmit={searchOrder}>
          <input aria-label="Tìm theo mã đơn hàng" placeholder="Nhập mã đơn hàng để tìm khách hàng..." value={orderSearch} onChange={(event) => setOrderSearch(event.target.value)} />
          <button className="filter-chip active" type="submit" disabled={orderLoading || !orderSearch.trim()}>{orderLoading ? "Đang tìm..." : "Tìm đơn"}</button>
          {(selectedCustomer || orderSearch.trim() || customerOrders.length > 0) && <button className="filter-chip" type="button" onClick={() => { setSelectedCustomer(null); setCustomerOrders([]); setOrderResultTotal(0); setOrderSearch(""); }}>Đóng kết quả</button>}
        </form>
        <div className="table-scroll"><table className="data-table"><thead><tr><th>#</th><th>Khách hàng</th><th>Email</th><th>Số đơn</th><th>Doanh thu</th></tr></thead><tbody>
          {!customers.customers.length && <tr><td colSpan={5} className="report-empty">Không có dữ liệu khách hàng.</td></tr>}
          {customers.customers.map((item, index) => <tr className="report-customer-row" key={item.customerId} onClick={() => void showCustomerOrders(item.customerId, item.customerName)}><td>{((customers.meta ?? EMPTY_META).page - 1) * (customers.meta ?? EMPTY_META).limit + index + 1}</td><td><button className="report-customer-link" type="button">{item.customerName}</button></td><td>{item.email}</td><td>{item.totalOrders.toLocaleString("vi-VN")}</td><td>{money(item.totalRevenue)}</td></tr>)}
        </tbody></table></div>
        <ReportPager label="khách hàng" meta={customers.meta ?? EMPTY_META} loading={loading} onPage={loadCustomerPage} />
        {(selectedCustomer || orderSearch.trim() || customerOrders.length > 0) && <OrderResults title={selectedCustomer ? `Đơn hàng của ${selectedCustomer.name}` : `Kết quả cho mã đơn “${orderSearch.trim()}”`} orders={customerOrders} total={orderResultTotal} page={orderPage} totalPages={orderTotalPages} loading={orderLoading} onPage={loadOrderPage} />}
      </section>

      <section className="card panel">
        <div className="panel-header"><div><h2>Bán hàng theo mặt hàng</h2><p className="panel-copy">Số lượng và doanh thu từ các đơn đã giao. Nhấn vào mặt hàng để xem các đơn đã mua.</p></div></div>
        <form className="report-order-search" onSubmit={searchProductOrder}>
          <input aria-label="Tìm đơn hàng trong báo cáo mặt hàng" placeholder="Nhập mã đơn hàng để xem các sản phẩm đã mua..." value={productOrderSearch} onChange={(event) => setProductOrderSearch(event.target.value)} />
          <button className="filter-chip active" type="submit" disabled={orderLoading || !productOrderSearch.trim()}>{orderLoading ? "Đang tìm..." : "Tìm đơn"}</button>
          {(selectedProduct || productOrderSearch.trim() || productOrders.length > 0) && <button className="filter-chip" type="button" onClick={closeProductOrders}>Đóng kết quả</button>}
        </form>
        <div className="table-scroll"><table className="data-table"><thead><tr><th>#</th><th>Mặt hàng</th><th>Số lượng bán</th><th>Doanh thu</th></tr></thead><tbody>
          {!products.bestSelling.length && <tr><td colSpan={4} className="report-empty">Không có dữ liệu mặt hàng.</td></tr>}
          {products.bestSelling.map((item, index) => <tr className="report-customer-row" key={item.productId} onClick={() => void showProductOrders(item.productId, item.productName)}><td>{((products.meta ?? EMPTY_META).page - 1) * (products.meta ?? EMPTY_META).limit + index + 1}</td><td><button className="report-customer-link" type="button">{item.productName}</button></td><td>{item.totalQuantitySold.toLocaleString("vi-VN")}</td><td>{money(item.totalRevenue)}</td></tr>)}
        </tbody></table></div>
        <ReportPager label="mặt hàng" meta={products.meta ?? EMPTY_META} loading={loading} onPage={loadProductPage} />
        {(selectedProduct || productOrderSearch.trim() || productOrders.length > 0) && <OrderResults title={selectedProduct ? `Đơn hàng có mua ${selectedProduct.name}` : `Kết quả cho mã đơn “${productOrderSearch.trim()}”`} orders={productOrders} total={productOrderTotal} page={productOrderPage} totalPages={productOrderTotalPages} loading={orderLoading} onPage={loadProductOrderPage} />}
      </section>
    </main>
  );
}

function OrderResults({ title, orders, total, page, totalPages, loading, onPage }: { title: string; orders: AdminOrderResponse[]; total: number; page: number; totalPages: number; loading: boolean; onPage: (page: number) => Promise<void> }) {
  return <div className="report-order-results">
    <div className="report-order-results-head"><h3>{title}</h3><span>{total.toLocaleString("vi-VN")} đơn hàng</span></div>
    {loading ? <p className="report-empty">Đang tải đơn hàng...</p> : !orders.length ? <p className="report-empty">Không tìm thấy đơn hàng phù hợp.</p> : <div className="table-scroll"><table className="data-table"><thead><tr><th>Mã đơn</th><th>Khách hàng</th><th>Mặt hàng đã mua</th><th>Ngày đặt</th><th>Trạng thái</th><th>Tổng tiền</th></tr></thead><tbody>
      {orders.map((order) => <tr key={order.id}><td><a className="report-order-code" href={`/orders?keyword=${encodeURIComponent(order.orderCode)}`}>{order.orderCode}</a></td><td>{order.customerName}<div className="table-subtle">{order.customerPhone || "—"}</div></td><td>{order.items.map((item) => <div className="report-order-item" key={item.id}>{item.productName} <span>× {item.quantity}</span></div>)}</td><td>{new Date(order.createdAt).toLocaleString("vi-VN")}</td><td>{order.orderStatus}</td><td>{money(order.totalAmount)}</td></tr>)}
    </tbody></table></div>}
    {totalPages > 1 && <ReportPager label="đơn hàng" meta={{ page, limit: 20, total, totalPages }} loading={loading} onPage={onPage} />}
  </div>;
}

function ReportPager({ label, meta, loading, onPage }: { label: string; meta: { page: number; limit: number; total: number; totalPages: number }; loading: boolean; onPage: (page: number) => Promise<void> }) {
  return <div className="admin-pager">
    <span className="admin-pager-info">Tổng {meta.total.toLocaleString("vi-VN")} {label}</span>
    <div className="admin-pager-controls">
      <button className="admin-pager-btn" type="button" disabled={loading || meta.page <= 1} onClick={() => void onPage(meta.page - 1)}>Trước</button>
      <span className="admin-pager-page">Trang {meta.page}/{Math.max(1, meta.totalPages)}</span>
      <button className="admin-pager-btn" type="button" disabled={loading || meta.page >= meta.totalPages} onClick={() => void onPage(meta.page + 1)}>Sau</button>
    </div>
  </div>;
}
