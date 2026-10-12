"use client";

import { useMemo, useState } from "react";
import type { AuditLogResponse } from "@/modules/audit/types";

const ENTITY_TYPES = [
  "PRODUCT",
  "CATEGORY",
  "BRAND",
  "ORDER",
  "USER",
  "VARIANT",
  "INVENTORY",
  "COUPON",
  "RETURN",
] as const;

export function AdminAuditLogsClient({ initialLogs }: { initialLogs: AuditLogResponse[] }) {
  const [actionFilter, setActionFilter] = useState("");
  const [entityTypeFilter, setEntityTypeFilter] = useState("");
  const [actorFilter, setActorFilter] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);

  const filtered = useMemo(() => {
    return initialLogs.filter((log) => {
      if (actionFilter && !log.action.toLowerCase().includes(actionFilter.toLowerCase())) return false;
      if (entityTypeFilter && log.entityType !== entityTypeFilter) return false;
      if (actorFilter && !(log.actorName ?? "").toLowerCase().includes(actorFilter.toLowerCase())) return false;
      return true;
    });
  }, [initialLogs, actionFilter, entityTypeFilter, actorFilter]);

  return (
    <section className="card panel">
      <div className="panel-header">
        <div>
          <h2>Nhật ký thao tác</h2>
          <p className="panel-copy">
            {filtered.length}/{initialLogs.length} bản ghi (hiển thị tối đa 50 mục gần nhất từ server)
          </p>
        </div>
      </div>

      {/* Bộ lọc */}
      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", marginBottom: 16 }}>
        <input
          className="admin-input"
          style={{ flex: "1 1 160px", minWidth: 140 }}
          placeholder="Lọc theo action..."
          value={actionFilter}
          onChange={(e) => setActionFilter(e.target.value)}
        />
        <select
          className="select"
          style={{ flex: "0 0 auto" }}
          value={entityTypeFilter}
          onChange={(e) => setEntityTypeFilter(e.target.value)}
        >
          <option value="">Tất cả loại entity</option>
          {ENTITY_TYPES.map((t) => (
            <option value={t} key={t}>{t}</option>
          ))}
        </select>
        <input
          className="admin-input"
          style={{ flex: "1 1 160px", minWidth: 140 }}
          placeholder="Lọc theo tên người thao tác..."
          value={actorFilter}
          onChange={(e) => setActorFilter(e.target.value)}
        />
        {(actionFilter || entityTypeFilter || actorFilter) && (
          <button
            className="admin-btn secondary"
            type="button"
            onClick={() => { setActionFilter(""); setEntityTypeFilter(""); setActorFilter(""); }}
          >
            Xoá bộ lọc
          </button>
        )}
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Thời gian</th>
            <th>Người thao tác</th>
            <th>Action</th>
            <th>Entity</th>
            <th>IP</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {filtered.length === 0 && (
            <tr>
              <td colSpan={6} style={{ textAlign: "center", color: "var(--admin-muted)" }}>
                Không tìm thấy bản ghi nào khớp với bộ lọc.
              </td>
            </tr>
          )}
          {filtered.map((item) => (
            <>
              <tr
                key={item.id}
                style={{ cursor: item.beforeJson || item.afterJson ? "pointer" : "default" }}
                onClick={() => {
                  if (item.beforeJson || item.afterJson) {
                    setExpanded((prev) => (prev === item.id ? null : item.id));
                  }
                }}
              >
                <td>{new Date(item.createdAt).toLocaleString("vi-VN")}</td>
                <td>
                  <strong>{item.actorName ?? "Không rõ"}</strong>
                  <div className="table-subtle">{item.actorUserId ?? "—"}</div>
                </td>
                <td>
                  <span className={`status ${item.action.startsWith("CREATE") ? "active" : item.action.startsWith("DELETE") ? "inactive" : "draft"}`}>
                    {item.action}
                  </span>
                </td>
                <td>
                  {item.entityType}
                  <div className="table-subtle">{item.entityId ?? "—"}</div>
                </td>
                <td>{item.ipAddress ?? "—"}</td>
                <td style={{ color: "var(--admin-muted)", fontSize: 12 }}>
                  {(item.beforeJson || item.afterJson) ? (expanded === item.id ? "▲ Ẩn" : "▼ Chi tiết") : ""}
                </td>
              </tr>
              {expanded === item.id && (
                <tr key={`${item.id}-detail`}>
                  <td colSpan={6} style={{ background: "var(--admin-surface)", padding: "12px 16px" }}>
                    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
                      {item.beforeJson && (
                        <div>
                          <p style={{ fontSize: 12, fontWeight: 700, marginBottom: 6, color: "var(--admin-muted)" }}>Trước khi thay đổi</p>
                          <pre style={{ fontSize: 11, margin: 0, whiteSpace: "pre-wrap", wordBreak: "break-all" }}>
                            {JSON.stringify(item.beforeJson, null, 2)}
                          </pre>
                        </div>
                      )}
                      {item.afterJson && (
                        <div>
                          <p style={{ fontSize: 12, fontWeight: 700, marginBottom: 6, color: "var(--admin-muted)" }}>Sau khi thay đổi</p>
                          <pre style={{ fontSize: 11, margin: 0, whiteSpace: "pre-wrap", wordBreak: "break-all" }}>
                            {JSON.stringify(item.afterJson, null, 2)}
                          </pre>
                        </div>
                      )}
                      {item.userAgent && (
                        <div style={{ gridColumn: "1 / -1" }}>
                          <span style={{ fontSize: 11, color: "var(--admin-muted)" }}>User-agent: {item.userAgent}</span>
                        </div>
                      )}
                    </div>
                  </td>
                </tr>
              )}
            </>
          ))}
        </tbody>
      </table>
    </section>
  );
}
