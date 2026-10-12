import { Suspense } from "react";
import { AdminOrdersClient } from "@/components/orders/AdminOrdersClient";
import { listAdminOrders } from "@/modules/orders/api";

type OrdersSearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function OrdersPage({ searchParams }: { searchParams: OrdersSearchParams }) {
  const params = await searchParams;
  const requestedPage = Number(Array.isArray(params.page) ? params.page[0] : params.page);
  const page = Number.isInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1;
  const keyword = (Array.isArray(params.keyword) ? params.keyword[0] : params.keyword)?.trim() ?? "";
  const status = (Array.isArray(params.status) ? params.status[0] : params.status) ?? "all";

  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>Đơn hàng</h1>
          <p>Theo dõi trạng thái xử lý, thanh toán, hoàn tiền và lịch sử đơn hàng.</p>
        </div>
      </section>

      <Suspense fallback={<OrderTableSkeleton />}>
        <OrdersContent page={page} keyword={keyword} status={status} />
      </Suspense>
    </main>
  );
}

async function OrdersContent({ page, keyword, status }: { page: number; keyword: string; status: string }) {
  const orderResult = await listAdminOrders({ page, limit: 20, keyword: keyword || undefined, status: status === "all" ? undefined : status })
    .then((data) => ({ data, error: null }))
    .catch(() => ({ data: { items: [], meta: { page, limit: 20, total: 0, totalPages: 0 } }, error: "Không thể tải đơn hàng. Vui lòng kiểm tra backend hoặc đăng nhập lại." }));
  const orderPage = orderResult.data;

  return (
    <AdminOrdersClient
      key={`${page}:${keyword}:${status}:${orderPage.items.map((item) => item.id).join(",")}`}
      initialOrders={orderPage.items}
      pageMeta={orderPage.meta}
      initialKeyword={keyword}
      initialStatus={status}
      loadError={orderResult.error}
    />
  );
}

function OrderTableSkeleton() {
  return (
    <div className="card panel">
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 18, gap: 10 }}>
        <div style={{ display: "flex", gap: 8 }}>
          {[90, 80, 100, 90].map((w, i) => (
            <div key={i} className="skeleton" style={{ width: w, height: 36, borderRadius: 999 }} />
          ))}
        </div>
        <div className="skeleton" style={{ width: 140, height: 36, borderRadius: 12 }} />
      </div>
      {[1, 2, 3, 4, 5, 6, 7].map((row) => (
        <div
          key={row}
          style={{ display: "grid", gridTemplateColumns: "1fr 2fr 1fr 1fr 1fr", gap: 12, alignItems: "center", padding: "13px 0", borderBottom: "1px solid var(--admin-line)" }}
        >
          <div className="skeleton" style={{ height: 13 }} />
          <div style={{ display: "grid", gap: 6 }}>
            <div className="skeleton" style={{ width: "80%", height: 13 }} />
            <div className="skeleton" style={{ width: "50%", height: 11 }} />
          </div>
          <div className="skeleton" style={{ width: 70, height: 22, borderRadius: 999 }} />
          <div className="skeleton" style={{ height: 13 }} />
          <div className="skeleton" style={{ height: 13 }} />
        </div>
      ))}
    </div>
  );
}
