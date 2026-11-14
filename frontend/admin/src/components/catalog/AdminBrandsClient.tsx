"use client";

import { useEffect, useState } from "react";
import { extractAdminError } from "@/modules/api/admin-errors";
import { createBrand, deleteBrand, updateBrand } from "@/modules/catalog-admin/browser-api";
import type { BrandResponse } from "@/modules/catalog-admin/types";
import { toSlug } from "@/modules/utils/slug";

function createEmptyForm() {
  return {
    name: "",
    slug: "",
    description: "",
    status: "ACTIVE"
  };
}

function toStatusLabel(status: string) {
  return status === "ACTIVE" ? "Hoạt động" : "Ẩn";
}

export function AdminBrandsClient({ initialItems }: { initialItems: BrandResponse[] }) {
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
      status: first.status
    });
  }, [initialItems]);

  function handleSelect(item: BrandResponse) {
    setSelectedId(item.id);
    setSlugDirty(true);
    setForm({
      name: item.name,
      slug: item.slug,
      description: item.description ?? "",
      status: item.status
    });
    setMessage(null);
  }

  function startCreate() {
    setSelectedId("");
    setSlugDirty(false);
    setForm(createEmptyForm());
    setMessage("Bạn đang tạo thương hiệu mới.");
  }

  async function handleSubmit() {
    try {
      setSaving(true);
      setMessage(null);

      if (selectedId) {
        const updated = await updateBrand(selectedId, {
          name: form.name,
          slug: form.slug,
          description: form.description || null,
          status: form.status
        });
        setItems((current) => current.map((item) => (item.id === updated.id ? updated : item)));
        setMessage(`Đã cập nhật thương hiệu ${updated.name}.`);
        return;
      }

      const created = await createBrand({
        name: form.name,
        slug: form.slug,
        description: form.description || null,
        status: form.status
      });
      setItems((current) => [created, ...current]);
      setSelectedId(created.id);
      setSlugDirty(true);
      setForm({
        name: created.name,
        slug: created.slug,
        description: created.description ?? "",
        status: created.status
      });
      setMessage(`Đã tạo thương hiệu ${created.name}.`);
    } catch (error) {
      setMessage(extractAdminError(error, "Không lưu được thương hiệu"));
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!selectedId) return;
    if (!window.confirm("Xóa thương hiệu này? Hành động không thể hoàn tác.")) return;
    try {
      setSaving(true);
      await deleteBrand(selectedId);
      const remaining = items.filter((item) => item.id !== selectedId);
      setItems(remaining);
      setSelectedId(remaining[0]?.id ?? "");
      if (remaining[0]) {
        setForm({ name: remaining[0].name, slug: remaining[0].slug, description: remaining[0].description ?? "", status: remaining[0].status });
      } else {
        setForm(createEmptyForm());
      }
      setMessage("Đã xóa thương hiệu.");
    } catch (error) {
      setMessage(extractAdminError(error, "Không xóa được thương hiệu"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="admin-grid admin-grid-2">
      <section className="card panel">
        <div className="panel-header">
          <div>
            <h2>Danh sách thương hiệu</h2>
            <p className="panel-copy">Quản lý thương hiệu dùng chung cho storefront và toàn bộ catalog sản phẩm.</p>
          </div>
          <button className="admin-btn" type="button" onClick={startCreate}>
            Tạo thương hiệu
          </button>
        </div>

        {items.length === 0 ? (
          <div className="empty-state">Hiện chưa có thương hiệu nào. Hãy tạo thương hiệu đầu tiên để bắt đầu.</div>
        ) : (
          <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Tên</th>
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
            <h2>{selectedId ? "Cập nhật thương hiệu" : "Tạo thương hiệu mới"}</h2>
            <p className="panel-copy">Slug có thể nhập tay hoặc tự sinh theo tên khi bạn chưa chỉnh thủ công.</p>
          </div>
        </div>
        {message ? <p className="action-message">{message}</p> : null}
        <div className="admin-form-grid">
          <input
            className="admin-input"
            placeholder="Tên thương hiệu"
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
          <div className="admin-form-full">
            <textarea
              className="admin-textarea"
              placeholder="Mô tả"
              value={form.description}
              onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
            />
          </div>
        </div>
        <div className="page-actions">
          {selectedId && (
            <button className="admin-btn secondary" type="button" onClick={() => void handleDelete()} disabled={saving}>
              Xóa thương hiệu
            </button>
          )}
          <button className="admin-btn" type="button" onClick={() => void handleSubmit()} disabled={saving}>
            {saving ? "Đang lưu..." : selectedId ? "Cập nhật thương hiệu" : "Tạo thương hiệu"}
          </button>
        </div>
      </section>
    </div>
  );
}
