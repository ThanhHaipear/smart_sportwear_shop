import { browserApiRequest } from "@/modules/api/browser-client";
import { adminEndpoints } from "@/modules/api/endpoints";
import { normalizeRevenueReport } from "@/modules/analytics/revenue-report";
import type { RevenueGranularity, RevenueReport } from "@/modules/analytics/revenue-report";

/** Client-side revenue fetch used by the dashboard granularity filter. */
export async function fetchRevenueReport(granularity: RevenueGranularity): Promise<RevenueReport> {
  const report = await browserApiRequest<unknown>(adminEndpoints.revenueReport, {
    method: "GET",
    query: { granularity }
  });
  return normalizeRevenueReport(report, granularity);
}
