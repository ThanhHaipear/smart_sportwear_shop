import { apiRequest, shouldUseMockApi } from "@/modules/api/client";
import { adminEndpoints } from "@/modules/api/endpoints";
import { stockAlerts, topProducts } from "@/modules/analytics/dashboard-data";

type OverviewReportResponse = {
  grossRevenue: number;
  realizedRevenue: number;
  totalOrders: number;
  pendingOrders: number;
  lowStockCount: number;
};

type LegacyOverviewReportResponse = {
  revenue?: unknown;
  orders?: unknown;
  activeOrders?: unknown;
  customers?: unknown;
};

function finiteNumber(value: unknown, fallback = 0): number {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function normalizeOverviewReport(value: unknown): OverviewReportResponse {
  const report = (value && typeof value === "object" ? value : {}) as
    Partial<OverviewReportResponse> & LegacyOverviewReportResponse;
  const revenue = finiteNumber(report.grossRevenue ?? report.revenue);

  return {
    grossRevenue: revenue,
    realizedRevenue: finiteNumber(report.realizedRevenue, revenue),
    totalOrders: finiteNumber(report.totalOrders ?? report.orders),
    pendingOrders: finiteNumber(report.pendingOrders ?? report.activeOrders),
    lowStockCount: finiteNumber(report.lowStockCount)
  };
}

export async function getOverviewReport(): Promise<OverviewReportResponse | null> {
  try {
    const report = await apiRequest<unknown>(adminEndpoints.overview, { next: { revalidate: 30 } });
    return normalizeOverviewReport(report);
  } catch {
    return null;
  }
}

export type RevenueGranularity = "DAY" | "MONTH" | "YEAR";

export type RevenuePoint = {
  label: string;
  date: string;
  revenue: number;
  orders: number;
};

export type RevenueReport = {
  granularity: RevenueGranularity;
  dateFrom: string;
  dateTo: string;
  points: RevenuePoint[];
};

type LegacyRevenueReport = {
  items?: Array<{ date?: unknown; revenue?: unknown; orders?: unknown }>;
};

function normalizeRevenueReport(value: unknown, granularity: RevenueGranularity): RevenueReport {
  const report = (value && typeof value === "object" ? value : {}) as Partial<RevenueReport> & LegacyRevenueReport;
  const source = Array.isArray(report.points) ? report.points : Array.isArray(report.items) ? report.items : [];
  const points = source.map((point) => {
    const date = typeof point.date === "string" ? point.date : String(point.date ?? "");
    return {
      label: "label" in point && typeof point.label === "string" ? point.label : date,
      date,
      revenue: finiteNumber(point.revenue),
      orders: finiteNumber(point.orders)
    };
  });

  return {
    granularity: report.granularity === "DAY" || report.granularity === "MONTH" || report.granularity === "YEAR"
      ? report.granularity
      : granularity,
    dateFrom: typeof report.dateFrom === "string" ? report.dateFrom : points[0]?.date ?? "",
    dateTo: typeof report.dateTo === "string" ? report.dateTo : points.at(-1)?.date ?? "",
    points
  };
}

/** Server-side fetch of the real revenue time series (default MONTH granularity). */
export async function getRevenueReport(granularity: RevenueGranularity = "MONTH"): Promise<RevenueReport> {
  const report = await apiRequest<unknown>(adminEndpoints.revenueReport, {
    query: { granularity },
    next: { revalidate: 30 }
  });
  return normalizeRevenueReport(report, granularity);
}

export type TopProductPoint = (typeof topProducts)[number];
export type StockAlert = (typeof stockAlerts)[number];

type ProductReportResponse = {
  bestSelling: Array<{
    productId: string;
    productName: string;
    totalQuantitySold: number;
    totalRevenue: number;
  }>;
};

type InventoryReportResponse = {
  lowStockThreshold: number;
  lowStockItems: Array<{
    variantId: string;
    sku: string;
    productName: string;
    stockQuantity: number;
    reservedQuantity: number;
    availableQuantity: number;
  }>;
};

export async function getDashboardData() {
  if (!shouldUseMockApi()) {
    const [productResult, inventoryResult] = await Promise.allSettled([
      apiRequest<ProductReportResponse>(adminEndpoints.topProducts, { next: { revalidate: 30 } }),
      apiRequest<InventoryReportResponse>(adminEndpoints.inventoryReport, { next: { revalidate: 30 } })
    ]);
    const productReport = productResult.status === "fulfilled" ? productResult.value : null;
    const inventoryReport = inventoryResult.status === "fulfilled" ? inventoryResult.value : null;
    const lowStockItems = Array.isArray(inventoryReport?.lowStockItems) ? inventoryReport.lowStockItems : [];

    return {
      topProducts: Array.isArray(productReport?.bestSelling)
        ? productReport.bestSelling.slice(0, 4).map((item) => ({
          name: item.productName,
          sales: finiteNumber(item.totalQuantitySold)
        }))
        : [],
      stockAlerts: lowStockItems.slice(0, 8).map((item) => ({
          sku: item.sku,
          product: item.productName,
          stock: finiteNumber(item.availableQuantity),
          forecast: finiteNumber(item.stockQuantity) + finiteNumber(item.reservedQuantity),
          status: finiteNumber(item.availableQuantity) <= Math.max(1, Math.floor(finiteNumber(inventoryReport?.lowStockThreshold) / 2)) ? "critical" : "low"
        }))
    };
  }

  return { topProducts, stockAlerts };
}

export async function listStockAlerts() {
  if (!shouldUseMockApi()) {
    try {
      const inventoryReport = await apiRequest<InventoryReportResponse>(adminEndpoints.inventoryReport, { next: { revalidate: 30 } });
      return inventoryReport.lowStockItems.slice(0, 8).map((item) => ({
        sku: item.sku,
        product: item.productName,
        stock: item.availableQuantity,
        forecast: item.stockQuantity + item.reservedQuantity,
        status: item.availableQuantity <= Math.max(1, Math.floor(inventoryReport.lowStockThreshold / 2)) ? "critical" : "low"
      }));
    } catch {
      return stockAlerts;
    }
  }

  return stockAlerts;
}
