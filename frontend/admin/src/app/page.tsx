import { Suspense } from "react";
import { CurrencyCircleDollar, Package, Receipt, Users } from "@phosphor-icons/react/dist/ssr";
import { RevenuePanel } from "@/components/dashboard/RevenuePanel";
import { TopProductChart } from "@/components/ui/AdminCharts";
import { getDashboardData, getOverviewReport, getRevenueReport } from "@/modules/analytics/api";
import type { RevenueReport } from "@/modules/analytics/api";

function formatVND(amount: number | null | undefined): string {
  if (typeof amount !== "number" || !Number.isFinite(amount)) return "—";
  if (amount >= 1_000_000_000) return `${(amount / 1_000_000_000).toFixed(1)} tỷ`;
  if (amount >= 1_000_000) return `${(amount / 1_000_000).toFixed(1)} triệu`;
  if (amount >= 1_000) return `${(amount / 1_000).toFixed(0)}k`;
  return amount.toLocaleString("vi-VN");
}

export default function AdminDashboard() {
  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>Tổng quan vận hành</h1>
          <p>Dashboard quản trị đơn hàng, tồn kho và sản phẩm cho Điểm Đến Thể Thao.</p>
        </div>
      </section>

      <Suspense fallback={<KpiSkeleton />}>
        <DashboardKpis />
      </Suspense>

      <Suspense fallback={<ChartsSkeleton />}>
        <DashboardCharts />
      </Suspense>
    </main>
  );
}

async function DashboardKpis() {
  const overview = await getOverviewReport();

  const kpis = [
    {
      label: "Doanh thu",
      value: overview ? formatVND(overview.grossRevenue) : "—",
      trend: overview ? `${formatVND(overview.realizedRevenue)} thực nhận` : "Đang tải...",
      icon: CurrencyCircleDollar,
      warn: false,
    },
    {
      label: "Tổng đơn hàng",
      value: overview ? overview.totalOrders.toLocaleString("vi-VN") : "—",
      trend: overview ? `${overview.pendingOrders} đang chờ xử lý` : "Đang tải...",
      icon: Receipt,
      warn: false,
    },
    {
      label: "Cảnh báo kho",
      value: overview ? `${overview.lowStockCount} SKU` : "—",
      trend: overview && overview.lowStockCount > 0 ? "Cần nhập thêm" : "Tồn kho ổn định",
      icon: Package,
      warn: (overview?.lowStockCount ?? 0) > 0,
    },
    {
      label: "Khách hàng",
      value: "—",
      trend: "Xem mục Khách hàng",
      icon: Users,
      warn: false,
    },
  ];

  return (
    <section className="kpi-grid" aria-label="KPI tổng quan">
      {kpis.map((kpi) => {
        const Icon = kpi.icon;
        return (
          <article className="card kpi-card" key={kpi.label}>
            <div className="kpi-label">
              <span>{kpi.label}</span>
              <Icon size={22} weight="duotone" />
            </div>
            <div className="kpi-value">{kpi.value}</div>
            <span className={kpi.warn ? "trend warn" : "trend"}>{kpi.trend}</span>
          </article>
        );
      })}
    </section>
  );
}

const EMPTY_REVENUE_REPORT: RevenueReport = {
  granularity: "MONTH",
  dateFrom: "",
  dateTo: "",
  points: []
};

async function DashboardCharts() {
  const [dashboard, revenueReport] = await Promise.all([
    getDashboardData(),
    getRevenueReport("MONTH").catch(() => EMPTY_REVENUE_REPORT)
  ]);

  return (
    <>
      <section className="dashboard-grid">
        <RevenuePanel initialReport={revenueReport} />

        <article className="card panel">
          <div className="panel-header">
            <h2>Top sản phẩm bán chạy</h2>
          </div>
          <TopProductChart data={dashboard.topProducts} />
        </article>
      </section>

      <section className="card panel">
        <div className="panel-header">
          <h2>Cảnh báo tồn kho thấp</h2>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>SKU</th>
              <th>Sản phẩm</th>
              <th>Tồn khả dụng</th>
              <th>Trạng thái</th>
            </tr>
          </thead>
          <tbody>
            {dashboard.stockAlerts.length === 0 && (
              <tr>
                <td colSpan={4} style={{ textAlign: "center", color: "var(--admin-muted)" }}>
                  Tồn kho đang ổn định.
                </td>
              </tr>
            )}
            {dashboard.stockAlerts.map((item) => (
              <tr key={item.sku}>
                <td>{item.sku}</td>
                <td>{item.product}</td>
                <td>{item.stock}</td>
                <td>
                  <span className={`status ${item.status}`}>{item.status === "critical" ? "Critical" : "Low"}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}

function KpiSkeleton() {
  return (
    <div className="kpi-grid">
      {[1, 2, 3, 4].map((i) => (
        <div key={i} className="card kpi-card">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <div className="skeleton" style={{ width: 80, height: 12 }} />
            <div className="skeleton" style={{ width: 22, height: 22, borderRadius: 6 }} />
          </div>
          <div className="skeleton" style={{ width: 120, height: 30 }} />
          <div className="skeleton" style={{ width: 70, height: 20, borderRadius: 999 }} />
        </div>
      ))}
    </div>
  );
}

function ChartsSkeleton() {
  return (
    <>
      <div className="dashboard-grid">
        {[220, 180].map((w, i) => (
          <div key={i} className="card panel">
            <div className="skeleton" style={{ width: w, height: 20, marginBottom: 18 }} />
            <div className="skeleton" style={{ width: "100%", height: 200, borderRadius: 12 }} />
          </div>
        ))}
      </div>
      <div className="card panel">
        <div className="skeleton" style={{ width: 200, height: 20, marginBottom: 18 }} />
        {[1, 2, 3, 4].map((i) => (
          <div
            key={i}
            style={{ display: "grid", gridTemplateColumns: "1fr 2fr 1fr 1fr", gap: 16, padding: "13px 0", borderBottom: "1px solid var(--admin-line)" }}
          >
            <div className="skeleton" style={{ height: 13 }} />
            <div className="skeleton" style={{ height: 13 }} />
            <div className="skeleton" style={{ height: 13 }} />
            <div className="skeleton" style={{ width: 60, height: 22, borderRadius: 999 }} />
          </div>
        ))}
      </div>
    </>
  );
}
