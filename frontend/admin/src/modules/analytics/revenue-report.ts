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

function finiteNumber(value: unknown): number {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? number : 0;
}

export function normalizeRevenueReport(value: unknown, granularity: RevenueGranularity): RevenueReport {
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
