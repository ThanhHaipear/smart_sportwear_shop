"use client";

import { useDeferredValue, useMemo, useState } from "react";
import { ApiRequestError } from "@/modules/api/common";
import {
  createRefund, fetchAdminReturnDetail, fetchRefundsForReturn, listAdminReturnsPage,
  updateRefundStatus, updateReturnStatus
} from "@/modules/returns/browser-api";
import type { PageMeta, RefundResponse, ReturnItemResolutionDraft, ReturnResponse } from "@/modules/returns/types";

const returnStatuses = ["REQUESTED", "APPROVED", "REJECTED", "RECEIVED", "REFUNDED", "CANCELLED"] as const;
const nextReturnStatuses: Record<string, readonly string[]> = {
  REQUESTED: ["APPROVED", "REJECTED", "CANCELLED"],
  APPROVED: ["RECEIVED"], RECEIVED: [], REJECTED: [], REFUNDED: [], CANCELLED: []
};
const nextRefundStatuses: Record<string, readonly string[]> = {
  PENDING: ["PROCESSING", "COMPLETED", "FAILED", "CANCELLED"], PROCESSING: ["COMPLETED", "FAILED"],
  COMPLETED: [], FAILED: ["PROCESSING", "COMPLETED", "CANCELLED"], CANCELLED: []
};
const conditionStatuses = ["UNOPENED", "LIKE_NEW", "USED", "DAMAGED"] as const;
const resolutions = ["REFUND", "EXCHANGE", "STORE_CREDIT", "REJECT"] as const;

function extractError(error: unknown, fallback: string) {
  if (error instanceof ApiRequestError) {
    const payload = error.payload as { message?: string } | null;
    return payload?.message ?? fallback;
  }
  return fallback;
}

function initialResolutionDrafts(items: ReturnResponse[]) {
  return Object.fromEntries(items.flatMap((item) => item.items.map((line) => [line.id, {
    conditionStatus: (line.conditionStatus ?? "LIKE_NEW") as ReturnItemResolutionDraft["conditionStatus"],
    resolution: (line.resolution ?? "REFUND") as ReturnItemResolutionDraft["resolution"],
    refundAmount: line.refundAmount?.toString() ?? ""
  }]))) as Record<string, ReturnItemResolutionDraft>;
}

export function AdminReturnsClient({ initialReturns, initialMeta, initialRefundsByReturn }: {
  initialReturns: ReturnResponse[]; initialMeta: PageMeta; initialRefundsByReturn: Record<string, RefundResponse[]>;
}) {
  const [items, setItems] = useState(initialReturns);
  const [meta, setMeta] = useState(initialMeta);
  const [refundsByReturn, setRefundsByReturn] = useState(initialRefundsByReturn);
  const [statusDrafts, setStatusDrafts] = useState<Record<string, string>>(Object.fromEntries(initialReturns.map((item) => [item.id, item.status])));
  const [refundStatusDrafts, setRefundStatusDrafts] = useState<Record<string, string>>(Object.fromEntries(Object.values(initialRefundsByReturn).flat().map((item) => [item.id, item.status])));
  const [resolutionDrafts, setResolutionDrafts] = useState(initialResolutionDrafts(initialReturns));
  const [statusFilter, setStatusFilter] = useState("all");
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState<string | null>(null);

  const filteredItems = useMemo(() => {
    const query = deferredSearch.trim().toLowerCase();
    return query ? items.filter((item) => [item.returnCode, item.orderCode, item.reason, item.description ?? "", item.status].join(" ").toLowerCase().includes(query)) : items;
  }, [deferredSearch, items]);

  function replaceReturn(updated: ReturnResponse) {
    setItems((current) => current.map((item) => item.id === updated.id ? updated : item));
    setStatusDrafts((current) => ({ ...current, [updated.id]: updated.status }));
    setResolutionDrafts((current) => ({ ...current, ...initialResolutionDrafts([updated]) }));
  }

  async function loadPage(page: number) {
    try {
      setSaving("list"); setMessage(null);
      const result = await listAdminReturnsPage(page, meta.limit || 10, statusFilter === "all" ? undefined : statusFilter);
      setItems(result.items); setMeta(result.meta); setRefundsByReturn({});
      setStatusDrafts(Object.fromEntries(result.items.map((item) => [item.id, item.status])));
      setRefundStatusDrafts({});
      setResolutionDrafts(initialResolutionDrafts(result.items));
    } catch (error) { setMessage(extractError(error, "Không tải được danh sách đổi trả")); }
    finally { setSaving(null); }
  }

  async function loadDetail(id: string) {
    try {
      setSaving(`detail:${id}`); setMessage(null);
      const [detail, refunds] = await Promise.all([fetchAdminReturnDetail(id), fetchRefundsForReturn(id)]);
      replaceReturn(detail);
      setRefundsByReturn((current) => ({ ...current, [id]: refunds }));
      setRefundStatusDrafts((current) => ({ ...current, ...Object.fromEntries(refunds.map((refund) => [refund.id, refund.status])) }));
    }
    catch (error) { setMessage(extractError(error, "Không tải được chi tiết yêu cầu đổi trả")); }
    finally { setSaving(null); }
  }

  async function saveReturn(item: ReturnResponse) {
    const target = statusDrafts[item.id] ?? item.status;
    if (target === item.status) { setMessage("Hãy chọn trạng thái tiếp theo trước khi lưu."); return; }
    const payload: Record<string, unknown> = { status: target };
    if (target === "RECEIVED") {
      const lines = item.items.map((line) => ({ line, draft: resolutionDrafts[line.id] }));
      if (lines.some(({ draft }) => !draft || (draft.resolution === "REFUND" && (!draft.refundAmount || Number(draft.refundAmount) <= 0)))) {
        setMessage("Mỗi sản phẩm hoàn tiền phải có số tiền hoàn lớn hơn 0."); return;
      }
      payload.items = lines.map(({ line, draft }) => ({
        returnItemId: line.id, conditionStatus: draft.conditionStatus, resolution: draft.resolution,
        refundAmount: draft.resolution === "REFUND" ? Number(draft.refundAmount) : null
      }));
    }
    try {
      setSaving(`return:${item.id}`); setMessage(null);
      const updated = await updateReturnStatus(item.id, payload); replaceReturn(updated);
      setMessage(`Đã cập nhật yêu cầu ${updated.returnCode} sang ${updated.status}.`);
    } catch (error) { setMessage(extractError(error, "Không cập nhật được trạng thái đổi trả")); }
    finally { setSaving(null); }
  }

  async function addRefund(item: ReturnResponse) {
    try {
      setSaving(`refund-create:${item.id}`); setMessage(null);
      const created = await createRefund(item.id);
      setRefundsByReturn((current) => ({ ...current, [item.id]: [created, ...(current[item.id] ?? [])] }));
      setRefundStatusDrafts((current) => ({ ...current, [created.id]: created.status }));
      setMessage(`Đã tạo giao dịch hoàn tiền ${created.refundCode}.`);
    } catch (error) { setMessage(extractError(error, "Không tạo được hoàn tiền")); }
    finally { setSaving(null); }
  }

  async function saveRefund(returnId: string, refund: RefundResponse) {
    const target = refundStatusDrafts[refund.id] ?? refund.status;
    if (target === refund.status) { setMessage("Hãy chọn trạng thái hoàn tiền tiếp theo trước khi lưu."); return; }
    try {
      setSaving(`refund:${refund.id}`); setMessage(null);
      const updated = await updateRefundStatus(refund.id, { status: target });
      setRefundsByReturn((current) => ({ ...current, [returnId]: (current[returnId] ?? []).map((entry) => entry.id === updated.id ? updated : entry) }));
      setRefundStatusDrafts((current) => ({ ...current, [updated.id]: updated.status }));
      if (updated.status === "COMPLETED") replaceReturn(await fetchAdminReturnDetail(returnId));
      setMessage(`Đã cập nhật hoàn tiền ${updated.refundCode} sang ${updated.status}.`);
    } catch (error) { setMessage(extractError(error, "Không cập nhật được hoàn tiền")); }
    finally { setSaving(null); }
  }

  return <section className="card panel">
    <div className="panel-header"><div><h2>Quản lý đổi trả và hoàn tiền</h2><p className="panel-copy">Duyệt yêu cầu, kiểm định hàng nhận lại và xử lý hoàn tiền.</p></div></div>
    {message ? <p className="action-message">{message}</p> : null}
    <div className="admin-form-grid" style={{ marginBottom: 16 }}>
      <input className="admin-input" placeholder="Tìm trong trang theo mã đổi trả, mã đơn hoặc lý do" value={search} onChange={(event) => setSearch(event.target.value)} />
      <select className="select" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}><option value="all">Tất cả trạng thái</option>{returnStatuses.map((status) => <option key={status}>{status}</option>)}</select>
      <button className="admin-btn secondary" type="button" disabled={saving === "list"} onClick={() => void loadPage(1)}>{saving === "list" ? "Đang tải..." : "Áp dụng trạng thái"}</button>
    </div>
    <div className="admin-stack">
      {filteredItems.length === 0 ? <div className="empty-state">Không có yêu cầu đổi trả nào khớp bộ lọc.</div> : null}
      {filteredItems.map((item) => {
        const target = statusDrafts[item.id] ?? item.status;
        const refunds = refundsByReturn[item.id] ?? [];
        const canCreateRefund = item.status === "RECEIVED" && !refunds.some((refund) => ["PENDING", "PROCESSING", "COMPLETED"].includes(refund.status));
        return <article className="admin-subcard" key={item.id}>
          <div className="admin-return-head"><div><strong>{item.returnCode}</strong><div className="table-subtle">Đơn {item.orderCode} · {item.reason} · {new Date(item.createdAt).toLocaleString("vi-VN")}</div>{item.description ? <div className="table-subtle">{item.description}</div> : null}</div>
            <div className="admin-inline-form wrap">
              <select className="select" value={target} disabled={(nextReturnStatuses[item.status] ?? []).length === 0} onChange={(event) => setStatusDrafts((current) => ({ ...current, [item.id]: event.target.value }))}>
                {[item.status, ...(nextReturnStatuses[item.status] ?? [])].map((status) => <option key={status}>{status}</option>)}
              </select>
              <button className="admin-btn" type="button" disabled={saving === `return:${item.id}` || target === item.status} onClick={() => void saveReturn(item)}>{saving === `return:${item.id}` ? "Đang lưu..." : "Lưu trạng thái"}</button>
              <button className="admin-btn secondary" type="button" disabled={saving === `detail:${item.id}`} onClick={() => void loadDetail(item.id)}>Tải chi tiết</button>
              <button className="admin-btn secondary" type="button" disabled={!canCreateRefund || saving === `refund-create:${item.id}`} onClick={() => void addRefund(item)}>Tạo hoàn tiền</button>
            </div>
          </div>
          {target === "RECEIVED" && item.status === "APPROVED" ? <p className="table-subtle">Kiểm định đầy đủ từng mặt hàng trước khi xác nhận đã nhận hàng.</p> : null}
          <table className="data-table"><thead><tr><th>Sản phẩm</th><th>SKU / SL</th><th>Tình trạng</th><th>Hướng xử lý</th><th>Số tiền hoàn</th></tr></thead><tbody>
            {item.items.map((line) => { const draft = resolutionDrafts[line.id]; const editing = target === "RECEIVED" && item.status === "APPROVED"; return <tr key={line.id}>
              <td>{line.productName}</td><td>{line.sku} · x{line.quantity}</td>
              <td>{editing ? <select className="select" value={draft?.conditionStatus ?? "LIKE_NEW"} onChange={(event) => setResolutionDrafts((current) => ({ ...current, [line.id]: { ...(current[line.id] ?? { resolution: "REFUND", refundAmount: "" }), conditionStatus: event.target.value as ReturnItemResolutionDraft["conditionStatus"] } }))}>{conditionStatuses.map((value) => <option key={value}>{value}</option>)}</select> : line.conditionStatus ?? "-"}</td>
              <td>{editing ? <select className="select" value={draft?.resolution ?? "REFUND"} onChange={(event) => setResolutionDrafts((current) => ({ ...current, [line.id]: { ...(current[line.id] ?? { conditionStatus: "LIKE_NEW", refundAmount: "" }), resolution: event.target.value as ReturnItemResolutionDraft["resolution"] } }))}>{resolutions.map((value) => <option key={value}>{value}</option>)}</select> : line.resolution ?? "-"}</td>
              <td>{editing && draft?.resolution === "REFUND" ? <input className="admin-input" type="number" min="1" step="1000" placeholder="Số tiền" value={draft.refundAmount} onChange={(event) => setResolutionDrafts((current) => ({ ...current, [line.id]: { ...current[line.id], refundAmount: event.target.value } }))} /> : line.refundAmount != null ? `${Math.round(line.refundAmount).toLocaleString("vi-VN")}₫` : "-"}</td>
            </tr>; })}
          </tbody></table>
          <div className="admin-stack">{refunds.length ? refunds.map((refund) => (
            <div className="admin-inline-form wrap" key={refund.id}>
              <strong>{refund.refundCode}</strong>
              <span className="table-subtle">
                {refund.provider} · {Math.round(refund.amount).toLocaleString("vi-VN")}₫ · {refund.status}
                {refund.gatewayTransactionNo ? ` · GD: ${refund.gatewayTransactionNo}` : ""}
              </span>
              <select className="select" value={refundStatusDrafts[refund.id] ?? refund.status} disabled={(nextRefundStatuses[refund.status] ?? []).length === 0} onChange={(event) => setRefundStatusDrafts((current) => ({ ...current, [refund.id]: event.target.value }))}>
                {[refund.status, ...(nextRefundStatuses[refund.status] ?? [])].map((status) => <option key={status}>{status}</option>)}
              </select>
              <button className="admin-btn secondary" type="button" disabled={saving === `refund:${refund.id}` || (refundStatusDrafts[refund.id] ?? refund.status) === refund.status} onClick={() => void saveRefund(item.id, refund)}>Lưu hoàn tiền</button>
            </div>
          )) : <div className="empty-state">Nhấn “Tải chi tiết” để kiểm tra lịch sử hoàn tiền.</div>}</div>
        </article>;
      })}
    </div>
    <div className="admin-pager"><span className="admin-pager-info">Tổng {meta.total.toLocaleString("vi-VN")} yêu cầu</span><div className="admin-pager-controls"><button className="admin-pager-btn" type="button" disabled={saving === "list" || meta.page <= 1} onClick={() => void loadPage(meta.page - 1)}>Trước</button><span className="admin-pager-page">Trang {meta.page}/{Math.max(1, meta.totalPages)}</span><button className="admin-pager-btn" type="button" disabled={saving === "list" || meta.page >= meta.totalPages} onClick={() => void loadPage(meta.page + 1)}>Sau</button></div></div>
  </section>;
}
