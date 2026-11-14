"use client";

import { useEffect, useState } from "react";
import { extractAdminError } from "@/modules/api/admin-errors";
import { createCategory, deleteCategory, updateCategory } from "@/modules/catalog-admin/browser-api";
import type { CategoryResponse } from "@/modules/catalog-admin/types";
import { toSlug } from "@/modules/utils/slug";

function createEmptyForm() {
  return {
    name: "",
    slug: "",
    description: "",
    status: "ACTIVE",
    parentId: "",
    nodeType: "LEAF",
    sortOrder: 0
  };
}

function toStatusLabel(status: string) {
  return status === "ACTIVE" ? "Hoạt động" : "Ẩn";
}

function toNodeTypeLabel(nodeType?: string) {
  return nodeType === "GROUP" ? "Nhóm" : "Lá";
}

export function AdminCategoriesClient({ initialItems }: { initialItems: CategoryResponse[] }) {
  const [items, setItems] = useState(initialItems);
  const [selectedId, setSelectedId] = useState(initialItems[0]?.id ?? "");
  const [form, setForm] = useState(createEmptyForm());
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [slugDirty, setSlugDirty] = useState(false);

  useEffect(() => {
    if (initialItems.length === 0) {
      return;
    }

    const first = initialItems[0];
    setSelectedId(first.id);
    setSlugDirty(true);
      setForm({
        name: first.name,
        slug: first.slug,
        description: first.description ?? "",
        status: first.status,
        parentId: first.parentId ?? "",
        nodeType: first.nodeType ?? "LEAF",
        sortOrder: first.sortOrder ?? 0
      });
  }, [initialItems]);

  function handleSelect(item: CategoryResponse) {
    setSelectedId(item.id);
    setSlugDirty(true);
    setForm({
      name: item.name,
      slug: item.slug,
      description: item.description ?? "",
      status: item.status,
      parentId: item.parentId ?? "",
      nodeType: item.nodeType ?? "LEAF",
      sortOrder: item.sortOrder ?? 0
    });
    setMessage(null);
  }

  function startCreate() {
    setSelectedId("");
    setSlugDirty(false);
    setForm(createEmptyForm());
    setMessage("Bạn đang tạo danh mục mới.");
  }

  async function handleSubmit() {
    try {
      setSaving(true);
      setMessage(null);

      if (selectedId) {
        const updated = await updateCategory(selectedId, {
          name: form.name,
          slug: form.slug,
          description: form.description || null,
          status: form.status,
          parentId: form.parentId || null,
          clearParent: !form.parentId,
          nodeType: form.nodeType,
          sortOrder: Number(form.sortOrder) || 0
        });
        setItems((current) => current.map((item) => (item.id === updated.id ? updated : item)));
        setMessage(`Đã cập nhật danh mục ${updated.name}.`);
        return;
      }

      const created = await createCategory({
        name: form.name,
        slug: form.slug,
        description: form.description || null,
        status: form.status,
        parentId: form.parentId || null,
        nodeType: form.nodeType,
        sortOrder: Number(form.sortOrder) || 0
      });
      setItems((current) => [created, ...current]);
      setSelectedId(created.id);
      setSlugDirty(true);
      setForm({
        name: created.name,
        slug: created.slug,
        description: created.description ?? "",
        status: created.status,
        parentId: created.parentId ?? "",
        nodeType: created.nodeType ?? "LEAF",
        sortOrder: created.sortOrder ?? 0
      });
      setMessage(`Đã tạo danh mục ${created.name}.`);
    } catch (error) {
      setMessage(extractAdminError(error, "Không lưu được danh mục"));
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!selectedId) return;
    if (!window.confirm("Xóa danh mục này? Hành động không thể hoàn tác.")) return;
    try {
      setSaving(true);
      await deleteCategory(selectedId);
      const remaining = items.filter((item) => item.id !== selectedId);
      setItems(remaining);
      setSelectedId(remaining[0]?.id ?? "");
      if (remaining[0]) {
        setForm({
          name: remaining[0].name,
          slug: remaining[0].slug,
          description: remaining[0].description ?? "",
          status: remaining[0].status,
          parentId: remaining[0].parentId ?? "",
          nodeType: remaining[0].nodeType ?? "LEAF",
          sortOrder: remaining[0].sortOrder ?? 0
        });
      } else {
        setForm(createEmptyForm());
      }
      setMessage("Đã xóa danh mục.");
    } catch (error) {
      setMessage(extractAdminError(error, "Không xóa được danh mục"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="admin-grid admin-grid-2">
      <section className="card panel">
        <div className="panel-header">
          <div>
            <h2>Danh sách danh mục</h2>
            <p className="panel-copy">Quản lý danh mục dùng chung cho storefront và toàn bộ sản phẩm.</p>
          </div>
          <button className="admin-btn" type="button" onClick={startCreate}>
            Tạo danh mục
          </button>
        </div>

        {items.length === 0 ? (
          <div className="empty-state">Hiện chưa có danh mục nào. Hãy tạo danh mục đầu tiên để bắt đầu.</div>
        ) : (
          <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Tên</th>
                <th>Loại</th>
                <th>Cha</th>
                <th>Slug</th>
                <th>Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className={selectedId === item.id ? "row-selected" : ""} onClick={() => handleSelect(item)}>
                  <td>
                    <strong>{item.name}</strong>
                    {item.description ? <div className="table-subtle">{item.description}</div> : null}
                  </td>
                  <td>{toNodeTypeLabel(item.nodeType)}</td>
                  <td>{items.find((parent) => parent.id === item.parentId)?.name ?? "—"}</td>
                  <td>{item.slug}</td>
                  <td>{toStatusLabel(item.status)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </section>

      <section className="card panel">
        <div className="panel-header">
          <div>
            <h2>{selectedId ? "Cập nhật danh mục" : "Tạo danh mục mới"}</h2>
            <p className="panel-copy">Slug có thể nhập tay hoặc tự sinh theo tên khi bạn chưa chỉnh thủ công.</p>
          </div>
        </div>
        {message ? <p className="action-message">{message}</p> : null}
        {(() => {
          const parentOptions = items.filter(
            (item) =>
              item.id !== selectedId &&
              !item.parentId &&
              (item.nodeType ?? "LEAF") === "GROUP"
          );
          return (
        <div className="admin-form-grid">
          <input
            className="admin-input"
            placeholder="Tên danh mục"
            value={form.name}
            onChange={(event) => {
              const name = event.target.value;
              setForm((current) => ({ ...current, name, ...(!slugDirty && { slug: toSlug(name) }) }));
            }}
          />
          <input
            className="admin-input"
            placeholder="Slug"
            value={form.slug}
            onChange={(event) => {
              setSlugDirty(true);
              setForm((current) => ({ ...current, slug: event.target.value }));
            }}
          />
          <select className="select" value={form.status} onChange={(event) => setForm((current) => ({ ...current, status: event.target.value }))}>
            <option value="ACTIVE">Hoạt động</option>
            <option value="INACTIVE">Ẩn</option>
          </select>
          <select className="select" value={form.nodeType} onChange={(event) => setForm((current) => ({ ...current, nodeType: event.target.value as "GROUP" | "LEAF" }))}>
            <option value="LEAF">Danh mục lá</option>
            <option value="GROUP">Nhóm danh mục</option>
          </select>
          <select className="select" value={form.parentId} onChange={(event) => setForm((current) => ({ ...current, parentId: event.target.value }))}>
            <option value="">Không có danh mục cha</option>
            {parentOptions.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
          <input
            className="admin-input"
            type="number"
            placeholder="Thứ tự sắp xếp"
            value={form.sortOrder}
            onChange={(event) => setForm((current) => ({ ...current, sortOrder: Number(event.target.value || 0) }))}
          />
          <div className="admin-form-full">
            <textarea
              className="admin-textarea"
              placeholder="Mô tả"
              value={form.description}
              onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
            />
          </div>
        </div>
          );
        })()}
        <div className="page-actions">
          {selectedId && (
            <button className="admin-btn secondary" type="button" onClick={() => void handleDelete()} disabled={saving}>
              Xóa danh mục
            </button>
          )}
          <button className="admin-btn" type="button" onClick={() => void handleSubmit()} disabled={saving}>
            {saving ? "Đang lưu..." : selectedId ? "Cập nhật danh mục" : "Tạo danh mục"}
          </button>
        </div>
      </section>
    </div>
  );
}
