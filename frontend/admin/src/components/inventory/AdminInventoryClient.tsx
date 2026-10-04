"use client";

import Image from "next/image";
import { useDeferredValue, useEffect, useRef, useState } from "react";
import { ApiRequestError } from "@/modules/api/common";
import { adjustStock, exportStock, importStock, listInventoryPage, listInventoryTransactionPage } from "@/modules/inventory/browser-api";
import type { InventoryItemResponse, InventoryPage, InventoryTransactionResponse } from "@/modules/inventory/types";
import { NO_IMAGE } from "@/modules/ui/placeholder";

function extractError(error: unknown, fallback: string) {
  if (error instanceof ApiRequestError) {
    const payload = error.payload as { message?: string } | null;
    return payload?.message ?? fallback;
  }

  return fallback;
}

type ActionType = "IMPORT" | "EXPORT" | "ADJUSTMENT_UP" | "ADJUSTMENT_DOWN";

function variantLabel(item: InventoryItemResponse) {
  const variant = item.size || item.color ? ` · ${item.size ?? "-"}/${item.color ?? "-"}` : "";
  return `${item.sku} · ${item.productName}${variant}`;
}

// Single search box that doubles as the dropdown trigger: typing filters the
// option list shown right below the input instead of relying on a separate
// search field next to a plain <select>.
function VariantCombobox({
  options,
  value,
  onChange
}: {
  options: InventoryItemResponse[];
  value: string;
  onChange: (item: InventoryItemResponse) => void;
}) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [results, setResults] = useState(options);
  const [selectedItem, setSelectedItem] = useState<InventoryItemResponse | null>(options.find((item) => item.variantId === value) ?? null);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const deferredQuery = useDeferredValue(query);

  const selected = selectedItem?.variantId === value
    ? selectedItem
    : options.find((item) => item.variantId === value) ?? null;

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      setLoading(true);
      try {
        const page = await listInventoryPage(1, 20, deferredQuery.trim() || undefined);
        if (!cancelled) setResults(page.items);
      } catch {
        if (!cancelled) setResults([]);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }, 300);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [deferredQuery, open]);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  function selectItem(item: InventoryItemResponse) {
    setSelectedItem(item);
    onChange(item);
    setQuery("");
    setOpen(false);
  }

  return (
    <div className="combobox" ref={containerRef}>
      <input
        className="admin-input combobox-input"
        placeholder="Tìm SKU, sản phẩm, size, màu..."
        value={open ? query : (selected ? variantLabel(selected) : "")}
        onFocus={() => {
          setQuery("");
          setOpen(true);
        }}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
        }}
      />
      {open ? (
        <div className="combobox-list">
          {loading ? (
            <div className="combobox-empty">Đang tìm...</div>
          ) : results.length === 0 ? (
            <div className="combobox-empty">Không tìm thấy biến thể phù hợp</div>
          ) : (
            results.map((item) => (
              <button
                type="button"
                key={item.variantId}
                className={`combobox-option${item.variantId === value ? " active" : ""}`}
                onClick={() => selectItem(item)}
              >
                <Image
                  className="combobox-thumb"
                  src={item.thumbnail || NO_IMAGE}
                  alt={item.productName}
                  width={40}
                  height={40}
                  unoptimized
                  onError={(event) => {
                    const img = event.currentTarget as HTMLImageElement;
                    if (img.src !== NO_IMAGE) {
                      img.src = NO_IMAGE;
                    }
                  }}
                />
                <span className="combobox-option-text">
                  <strong>{item.productName}</strong>
                  <span className="table-subtle">
                    {item.sku}
                    {item.size || item.color ? ` · ${item.size ?? "-"}/${item.color ?? "-"}` : ""}
                  </span>
                </span>
                <span className={`combobox-stock${item.availableQuantity <= 0 ? " empty" : ""}`}>
                  <strong>{item.availableQuantity}</strong>
                  <small>khả dụng</small>
                </span>
              </button>
            ))
          )}
        </div>
      ) : null}
    </div>
  );
}

export function AdminInventoryClient({
  initialItemsPage,
  initialTransactionsPage
}: {
  initialItemsPage: InventoryPage<InventoryItemResponse>;
  initialTransactionsPage: InventoryPage<InventoryTransactionResponse>;
}) {
  const [items, setItems] = useState(initialItemsPage.items);
  const [itemsMeta, setItemsMeta] = useState(initialItemsPage.meta);
  const [transactions, setTransactions] = useState(initialTransactionsPage.items);
  const [transactionsMeta, setTransactionsMeta] = useState(initialTransactionsPage.meta);
  const [variantId, setVariantId] = useState(initialItemsPage.items[0]?.variantId ?? "");
  const [selectedInventoryItem, setSelectedInventoryItem] = useState<InventoryItemResponse | null>(initialItemsPage.items[0] ?? null);
  const [quantity, setQuantity] = useState(1);
  const [actionType, setActionType] = useState<ActionType>("IMPORT");
  const [note, setNote] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const [itemsLoading, setItemsLoading] = useState(false);
  const [transactionsLoading, setTransactionsLoading] = useState(false);
  const initialSearchRender = useRef(true);

  async function loadItems(page: number, keyword = deferredSearch.trim()) {
    setItemsLoading(true);
    try {
      const result = await listInventoryPage(page, 25, keyword || undefined);
      setItems(result.items);
      setItemsMeta(result.meta);
    } catch (error) {
      setMessage(extractError(error, "Không tải được danh sách tồn kho"));
    } finally {
      setItemsLoading(false);
    }
  }

  async function loadTransactions(page: number) {
    setTransactionsLoading(true);
    try {
      const result = await listInventoryTransactionPage(page, 20);
      setTransactions(result.items);
      setTransactionsMeta(result.meta);
    } catch (error) {
      setMessage(extractError(error, "Không tải được lịch sử giao dịch kho"));
    } finally {
      setTransactionsLoading(false);
    }
  }

  useEffect(() => {
    if (initialSearchRender.current) {
      initialSearchRender.current = false;
      return;
    }
    const timer = window.setTimeout(() => void loadItems(1, deferredSearch.trim()), 300);
    return () => window.clearTimeout(timer);
    // loadItems deliberately reads only the supplied keyword for this effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deferredSearch]);

  useEffect(() => {
    function handleStockChanged(event: Event) {
      const detail = (event as CustomEvent<{
        variantId: string;
        stockQuantity: number;
        reservedQuantity: number;
        availableQuantity: number;
      }>).detail;
      setItems((current) => current.map((item) => item.variantId === detail.variantId ? { ...item, ...detail } : item));
      setSelectedInventoryItem((current) => current?.variantId === detail.variantId ? { ...current, ...detail } : current);
    }
    window.addEventListener("inventory:stock-changed", handleStockChanged);
    return () => window.removeEventListener("inventory:stock-changed", handleStockChanged);
  }, []);

  async function handleSubmit() {
    const decreasesStock = actionType === "EXPORT" || actionType === "ADJUSTMENT_DOWN";
    if (decreasesStock && selectedInventoryItem && quantity > selectedInventoryItem.availableQuantity) {
      setMessage(`Số lượng tối đa có thể xuất/giảm là ${selectedInventoryItem.availableQuantity}.`);
      return;
    }
    try {
      setSaving(true);
      setMessage(null);
      let updated: InventoryItemResponse;

      if (actionType === "IMPORT") {
        updated = await importStock({ variantId, quantity, note: note.trim() || undefined });
      } else if (actionType === "EXPORT") {
        updated = await exportStock({ variantId, quantity, note: note.trim() || undefined });
      } else {
        updated = await adjustStock({ variantId, quantity, type: actionType, note: note.trim() || undefined });
      }

      setItems((current) => current.map((item) => (item.variantId === updated.variantId ? updated : item)));
      setSelectedInventoryItem(updated);
      try {
        await loadTransactions(1);
      } catch {
        // The stock mutation already succeeded. Keep the existing history instead
        // of fabricating an audit row with incorrect before/after quantities.
      }
      setMessage("Đã cập nhật tồn kho thành công.");
      setNote("");
    } catch (error) {
      setMessage(extractError(error, "Không cập nhật được tồn kho"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <section id="inventory-action-form" className="card panel">
        <div className="panel-header">
          <h2>Thao tác kho</h2>
        </div>
        {message ? <p className="action-message">{message}</p> : null}
        <div className="admin-inline-form wrap">
          <div style={{ width: 460, maxWidth: "100%" }}>
            <VariantCombobox
              options={items}
              value={variantId}
              onChange={(item) => {
                setVariantId(item.variantId);
                setSelectedInventoryItem(item);
              }}
            />
          </div>
          <select className="select" value={actionType} onChange={(event) => setActionType(event.target.value as ActionType)}>
            <option value="IMPORT">Nhập kho</option>
            <option value="EXPORT">Xuất kho</option>
            <option value="ADJUSTMENT_UP">Điều chỉnh tăng</option>
            <option value="ADJUSTMENT_DOWN">Điều chỉnh giảm</option>
          </select>
          <input
            className="admin-input"
            type="number"
            min={1}
            max={actionType === "EXPORT" || actionType === "ADJUSTMENT_DOWN" ? selectedInventoryItem?.availableQuantity : undefined}
            value={quantity}
            onChange={(event) => setQuantity(Number(event.target.value) || 1)}
          />
          <input className="admin-input" placeholder="Ghi chú" value={note} onChange={(event) => setNote(event.target.value)} />
          <button
            className="admin-btn"
            type="button"
            onClick={() => void handleSubmit()}
            disabled={saving || !variantId || ((actionType === "EXPORT" || actionType === "ADJUSTMENT_DOWN") && (selectedInventoryItem?.availableQuantity ?? 0) <= 0)}
          >
            {saving ? "Đang lưu..." : "Xác nhận"}
          </button>
        </div>
        {selectedInventoryItem ? (
          <div className="inventory-selection-summary" aria-live="polite">
            <div>
              <span>Biến thể đã chọn</span>
              <strong>{selectedInventoryItem.size ?? "Không size"} / {selectedInventoryItem.color ?? "Không màu"}</strong>
              <small>{selectedInventoryItem.sku}</small>
            </div>
            <div><span>Tồn thực</span><strong>{selectedInventoryItem.stockQuantity}</strong></div>
            <div><span>Đang giữ</span><strong>{selectedInventoryItem.reservedQuantity}</strong></div>
            <div className={selectedInventoryItem.availableQuantity <= 0 ? "is-empty" : ""}>
              <span>Khả dụng</span>
              <strong>{selectedInventoryItem.availableQuantity}</strong>
              <small>{selectedInventoryItem.availableQuantity <= 0 ? "Đã hết hàng" : "Có thể xuất/giảm"}</small>
            </div>
          </div>
        ) : null}
      </section>

      <section className="card panel">
        <div className="panel-header">
          <div>
            <h2>Tồn kho hiện tại</h2>
            <p className="panel-copy">Theo dõi số lượng thực, số lượng đang giữ và lượng khả dụng theo từng biến thể.</p>
          </div>
          <input
            className="admin-input"
            style={{ width: 280 }}
            placeholder="Tìm theo SKU, tên sản phẩm, size hoặc màu..."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <table className="data-table" aria-busy={itemsLoading}>
          <thead>
            <tr>
              <th>SKU</th>
              <th>Sản phẩm</th>
              <th>Size/Màu</th>
              <th>Tồn thực</th>
              <th>Đang giữ</th>
              <th>Khả dụng</th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={6} style={{ textAlign: "center", color: "var(--admin-muted)" }}>
                  Không có biến thể nào khớp bộ lọc hiện tại.
                </td>
              </tr>
            ) : null}
            {items.map((item) => (
              <tr key={item.variantId}>
                <td>{item.sku}</td>
                <td>{item.productName}</td>
                <td>
                  {item.size ?? "-"} / {item.color ?? "-"}
                </td>
                <td>{item.stockQuantity}</td>
                <td>{item.reservedQuantity}</td>
                <td>{item.availableQuantity}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="admin-pager">
          <span className="admin-pager-info">
            {itemsLoading ? "Đang tải..." : itemsMeta.total === 0 ? "Không có SKU" : `Hiển thị ${(itemsMeta.page - 1) * itemsMeta.limit + 1}–${Math.min(itemsMeta.page * itemsMeta.limit, itemsMeta.total)} trên ${itemsMeta.total} SKU`}
          </span>
          <div className="admin-pager-controls">
            <button className="admin-pager-btn" type="button" disabled={itemsLoading || itemsMeta.page <= 1} onClick={() => void loadItems(itemsMeta.page - 1)}>Trước</button>
            <span className="admin-pager-page">Trang {itemsMeta.page}/{Math.max(1, itemsMeta.totalPages)}</span>
            <button className="admin-pager-btn" type="button" disabled={itemsLoading || itemsMeta.page >= itemsMeta.totalPages} onClick={() => void loadItems(itemsMeta.page + 1)}>Sau</button>
          </div>
        </div>
      </section>

      <section className="card panel">
        <div className="panel-header">
          <h2>Giao dịch kho gần đây</h2>
        </div>
        <table className="data-table" aria-busy={transactionsLoading}>
          <thead>
            <tr>
              <th>Thời gian</th>
              <th>SKU</th>
              <th>Loại</th>
              <th>Số lượng</th>
              <th>Tồn trước → sau</th>
              <th>Khả dụng sau</th>
              <th>Ghi chú</th>
              <th>Người thao tác</th>
            </tr>
          </thead>
          <tbody>
            {transactions.length === 0 ? (
              <tr><td colSpan={8} style={{ textAlign: "center", color: "var(--admin-muted)" }}>Chưa có giao dịch kho.</td></tr>
            ) : null}
            {transactions.map((item) => (
              <tr key={item.id}>
                <td>{new Date(item.createdAt).toLocaleString("vi-VN")}</td>
                <td>{item.sku}</td>
                <td>{item.type}</td>
                <td>{item.quantity}</td>
                <td>{item.beforeStockQuantity} → {item.afterStockQuantity}</td>
                <td>{item.afterStockQuantity - item.afterReservedQuantity}</td>
                <td>{item.note || "-"}</td>
                <td>{item.createdByName ?? "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="admin-pager">
          <span className="admin-pager-info">
            {transactionsLoading ? "Đang tải..." : transactionsMeta.total === 0 ? "Chưa có giao dịch" : `Hiển thị ${(transactionsMeta.page - 1) * transactionsMeta.limit + 1}–${Math.min(transactionsMeta.page * transactionsMeta.limit, transactionsMeta.total)} trên ${transactionsMeta.total} giao dịch`}
          </span>
          <div className="admin-pager-controls">
            <button className="admin-pager-btn" type="button" disabled={transactionsLoading || transactionsMeta.page <= 1} onClick={() => void loadTransactions(transactionsMeta.page - 1)}>Trước</button>
            <span className="admin-pager-page">Trang {transactionsMeta.page}/{Math.max(1, transactionsMeta.totalPages)}</span>
            <button className="admin-pager-btn" type="button" disabled={transactionsLoading || transactionsMeta.page >= transactionsMeta.totalPages} onClick={() => void loadTransactions(transactionsMeta.page + 1)}>Sau</button>
          </div>
        </div>
      </section>
    </>
  );
}
