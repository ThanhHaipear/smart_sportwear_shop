"use client";

import Image from "next/image";
import { useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import { extractAdminError } from "@/modules/api/admin-errors";
import {
  addProductToCollection,
  createAdminProduct,
  createCollection,
  deleteCollection,
  listBrandsForPicker,
  listCategoriesForPicker,
  listCollectionProducts,
  listProductsForPicker,
  removeProductFromCollection,
  updateCollection
} from "@/modules/catalog-admin/browser-api";
import type { ProductPickItem } from "@/modules/catalog-admin/browser-api";
import type { BrandResponse, CategoryResponse, CollectionResponse, ProductDetailResponse } from "@/modules/catalog-admin/types";
import { NO_IMAGE } from "@/modules/ui/placeholder";
import { toSlug } from "@/modules/utils/slug";

const PLACEHOLDER_IMG = NO_IMAGE;

function getProductBrandName(product: ProductPickItem) {
  return product.brandName ?? product.brand?.name ?? null;
}

function getProductCategoryName(product: ProductPickItem) {
  return product.categoryName ?? product.category?.name ?? null;
}

function productStatusClass(status: string) {
  switch (status) {
    case "ACTIVE": return "active";
    case "INACTIVE": return "draft";
    default: return "draft";
  }
}

// Must match the backend CollectionStatus / CollectionType enums exactly, otherwise
// Jackson rejects the create/update body with "Malformed request body".
const STATUS_OPTIONS = ["DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED"] as const;
const COLLECTION_TYPES = ["SEASONAL", "SPORT", "CAMPAIGN", "CAPSULE", "NEW_ARRIVAL"] as const;
const PRODUCT_STATUS_OPTIONS = ["DRAFT", "ACTIVE", "INACTIVE"] as const;
const PRODUCT_GENDER_OPTIONS = ["MEN", "WOMEN", "UNISEX"] as const;

function createEmptyProductForm() {
  return {
    name: "",
    slug: "",
    categoryId: "",
    brandId: "",
    gender: "",
    sportType: "",
    status: "DRAFT",
    shortDescription: ""
  };
}

function productDetailToPickItem(detail: ProductDetailResponse): ProductPickItem {
  return {
    id: detail.id,
    name: detail.name,
    slug: detail.slug,
    status: detail.status,
    thumbnail: detail.images[0]?.imageUrl ?? null,
    brand: detail.brand ? { id: detail.brand.id, name: detail.brand.name } : null,
    category: detail.category ? { id: detail.category.id, name: detail.category.name } : null
  };
}

function createEmptyForm() {
  return {
    name: "",
    slug: "",
    description: "",
    shortDescription: "",
    collectionType: "SEASONAL",
    brandId: "",
    season: "",
    year: "",
    bannerImageUrl: "",
    coverImageUrl: "",
    status: "DRAFT",
    startsAt: "",
    endsAt: "",
    sortOrder: "0",
    isFeatured: false
  };
}

function toLocalDateTime(value: string | null) {
  if (!value) return "";
  return new Date(value).toISOString().slice(0, 16);
}

function toInstant(value: string) {
  return value ? new Date(value).toISOString() : null;
}

function toStatusLabel(status: string) {
  switch (status) {
    case "ACTIVE":
      return "Hoạt động";
    case "INACTIVE":
      return "Tạm ẩn";
    case "ARCHIVED":
      return "Lưu trữ";
    default:
      return "Nháp";
  }
}

function toStatusTone(status: string) {
  switch (status) {
    case "ACTIVE":
      return "success";
    case "INACTIVE":
      return "muted";
    case "ARCHIVED":
      return "muted";
    default:
      return "warning";
  }
}

function createAssignError(error: unknown) {
  return extractAdminError(error, "Không thể cập nhật danh sách sản phẩm của bộ sưu tập");
}

export function AdminCollectionsClient({ initialItems }: { initialItems: CollectionResponse[] }) {
  const [items, setItems] = useState(initialItems);
  const [selectedId, setSelectedId] = useState(initialItems[0]?.id ?? "");
  const [form, setForm] = useState(createEmptyForm());
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [slugDirty, setSlugDirty] = useState(false);
  const [allProducts, setAllProducts] = useState<ProductPickItem[]>([]);
  const [assigned, setAssigned] = useState<ProductPickItem[]>([]);
  // Products chosen while CREATING a new collection (no id yet): staged locally
  // and attached right after the collection is created.
  const [pending, setPending] = useState<ProductPickItem[]>([]);
  // "Gắn sản phẩm" panel has two tabs: pick an existing product, or create a
  // brand-new product that gets attached to this collection right away.
  const [productTab, setProductTab] = useState<"existing" | "new">("existing");
  const [categories, setCategories] = useState<CategoryResponse[]>([]);
  const [brands, setBrands] = useState<BrandResponse[]>([]);
  const [catalogLoaded, setCatalogLoaded] = useState(false);
  const [newProduct, setNewProduct] = useState(createEmptyProductForm());
  const [newProductSlugDirty, setNewProductSlugDirty] = useState(false);
  const [creatingProduct, setCreatingProduct] = useState(false);
  const [productSearch, setProductSearch] = useState("");
  const [collectionSearch, setCollectionSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<"all" | string>("all");
  const [brandFilter, setBrandFilter] = useState<"all" | string>("all");
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [assignMessage, setAssignMessage] = useState<string | null>(null);
  const deferredProductSearch = useDeferredValue(productSearch);
  const deferredCollectionSearch = useDeferredValue(collectionSearch);
  const assignedCacheRef = useRef<Record<string, ProductPickItem[]>>({});

  const selectedCollection = items.find((item) => item.id === selectedId) ?? null;
  // In edit mode the chosen products come from the server (assigned); in create
  // mode they are the locally staged ones (pending).
  const chosenProducts = selectedId ? assigned : pending;

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
      shortDescription: first.shortDescription ?? "",
      collectionType: first.collectionType,
      brandId: first.brand?.id ?? "",
      season: first.season ?? "",
      year: first.year != null ? String(first.year) : "",
      bannerImageUrl: first.bannerImageUrl ?? "",
      coverImageUrl: first.coverImageUrl ?? "",
      status: first.status,
      startsAt: toLocalDateTime(first.startsAt),
      endsAt: toLocalDateTime(first.endsAt),
      sortOrder: String(first.sortOrder ?? 0),
      isFeatured: Boolean(first.isFeatured)
    });
  }, [initialItems]);

  // Load brands once so the collection form's brand-tag select is populated.
  useEffect(() => {
    void ensureCatalogRefsLoaded();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedId) {
      setAssigned([]);
      return;
    }

    const cached = assignedCacheRef.current[selectedId];
    if (cached) {
      setAssigned(cached);
      return;
    }

    let active = true;
    setLoadingProducts(true);
    setAssignMessage(null);

    listCollectionProducts(selectedId)
      .then((data) => {
        if (!active) return;
        assignedCacheRef.current[selectedId] = data;
        setAssigned(data);
      })
      .catch((error) => {
        if (!active) return;
        setAssigned([]);
        setAssignMessage(createAssignError(error));
      })
      .finally(() => {
        if (active) {
          setLoadingProducts(false);
        }
      });

    return () => {
      active = false;
    };
  }, [selectedId]);

  const filteredCollections = useMemo(() => {
    const query = deferredCollectionSearch.trim().toLowerCase();
    return items.filter((item) => {
      const matchesStatus = statusFilter === "all" ? true : item.status === statusFilter;
      if (!matchesStatus) return false;
      const matchesBrand =
        brandFilter === "all"
          ? true
          : brandFilter === "none"
            ? item.brand == null
            : item.brand?.id === brandFilter;
      if (!matchesBrand) return false;
      if (!query) return true;
      return [item.name, item.slug, item.season ?? "", String(item.year ?? ""), item.collectionType, item.brand?.name ?? ""]
        .join(" ")
        .toLowerCase()
        .includes(query);
    });
  }, [deferredCollectionSearch, items, statusFilter, brandFilter]);

  const filteredProducts = useMemo(() => {
    const query = deferredProductSearch.trim().toLowerCase();
    const chosenIds = new Set(chosenProducts.map((item) => item.id));
    const candidates = allProducts.filter((item) => !chosenIds.has(item.id));
    if (!query) {
      return candidates.slice(0, 30);
    }

    return candidates
      .filter((item) => [item.name, item.slug, item.status].join(" ").toLowerCase().includes(query))
      .slice(0, 30);
  }, [allProducts, chosenProducts, deferredProductSearch]);

  async function ensureProductPickerLoaded() {
    if (allProducts.length > 0 || loadingProducts) {
      return;
    }

    try {
      setLoadingProducts(true);
      const data = await listProductsForPicker();
      setAllProducts(data);
    } catch (error) {
      setAssignMessage(createAssignError(error));
    } finally {
      setLoadingProducts(false);
    }
  }

  function handleSelect(item: CollectionResponse) {
    setSelectedId(item.id);
    setSlugDirty(true);
    setMessage(null);
    setAssignMessage(null);
    setPending([]);
    setForm({
      name: item.name,
      slug: item.slug,
      description: item.description ?? "",
      shortDescription: item.shortDescription ?? "",
      collectionType: item.collectionType,
      brandId: item.brand?.id ?? "",
      season: item.season ?? "",
      year: item.year != null ? String(item.year) : "",
      bannerImageUrl: item.bannerImageUrl ?? "",
      coverImageUrl: item.coverImageUrl ?? "",
      status: item.status,
      startsAt: toLocalDateTime(item.startsAt),
      endsAt: toLocalDateTime(item.endsAt),
      sortOrder: String(item.sortOrder ?? 0),
      isFeatured: Boolean(item.isFeatured)
    });
  }

  function startCreate() {
    setSelectedId("");
    setSlugDirty(false);
    setAssignMessage(null);
    setProductSearch("");
    setAssigned([]);
    setPending([]);
    setProductTab("existing");
    setNewProduct(createEmptyProductForm());
    setNewProductSlugDirty(false);
    setForm(createEmptyForm());
    setMessage("Bạn đang tạo bộ sưu tập mới.");
  }

  async function handleSubmit() {
    if (!form.name.trim()) {
      setMessage("Vui lòng nhập tên bộ sưu tập.");
      return;
    }

    if (!form.slug.trim()) {
      setMessage("Slug không được để trống.");
      return;
    }

    try {
      setSaving(true);
      setMessage(null);

      const payload = {
        name: form.name.trim(),
        slug: form.slug.trim(),
        description: form.description.trim() || null,
        shortDescription: form.shortDescription.trim() || null,
        collectionType: form.collectionType,
        brandId: form.brandId || null,
        season: form.season.trim() || null,
        year: form.year.trim() ? Number(form.year) : null,
        bannerImageUrl: form.bannerImageUrl.trim() || null,
        coverImageUrl: form.coverImageUrl.trim() || null,
        status: form.status,
        startsAt: toInstant(form.startsAt),
        endsAt: toInstant(form.endsAt),
        sortOrder: form.sortOrder.trim() ? Number(form.sortOrder) : 0,
        isFeatured: form.isFeatured
      };

      if (selectedId) {
        const updated = await updateCollection(selectedId, {
          ...payload,
          clearBrand: !form.brandId
        });
        setItems((current) => current.map((item) => (item.id === updated.id ? updated : item)));
        setMessage(`Đã cập nhật bộ sưu tập ${updated.name}.`);
        return;
      }

      const created = await createCollection(payload);

      // Attach any products staged during creation to the freshly created collection.
      const staged = pending;
      const attached: ProductPickItem[] = [];
      let failedCount = 0;
      for (const product of staged) {
        try {
          await addProductToCollection(product.id, created.id);
          attached.push(product);
        } catch {
          failedCount += 1;
        }
      }

      setItems((current) => [created, ...current]);
      setSelectedId(created.id);
      setSlugDirty(true);
      setForm({
        name: created.name,
        slug: created.slug,
        description: created.description ?? "",
        shortDescription: created.shortDescription ?? "",
        collectionType: created.collectionType,
        brandId: created.brand?.id ?? "",
        season: created.season ?? "",
        year: created.year != null ? String(created.year) : "",
        bannerImageUrl: created.bannerImageUrl ?? "",
        coverImageUrl: created.coverImageUrl ?? "",
        status: created.status,
        startsAt: toLocalDateTime(created.startsAt),
        endsAt: toLocalDateTime(created.endsAt),
        sortOrder: String(created.sortOrder ?? 0),
        isFeatured: Boolean(created.isFeatured)
      });
      assignedCacheRef.current[created.id] = attached;
      setAssigned(attached);
      setPending([]);
      setMessage(
        attached.length > 0
          ? `Đã tạo bộ sưu tập ${created.name} và thêm ${attached.length} sản phẩm${failedCount > 0 ? ` (${failedCount} sản phẩm lỗi)` : ""}.`
          : `Đã tạo bộ sưu tập ${created.name}.`
      );
    } catch (error) {
      setMessage(extractAdminError(error, "Không thể lưu bộ sưu tập"));
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!selectedId) return;
    if (!window.confirm("Xóa bộ sưu tập này? Hành động không thể hoàn tác.")) return;
    try {
      setSaving(true);
      await deleteCollection(selectedId);
      const remaining = items.filter((item) => item.id !== selectedId);
      setItems(remaining);
      setSelectedId(remaining[0]?.id ?? "");
      setMessage("Đã xóa bộ sưu tập.");
    } catch (error) {
      setMessage(extractAdminError(error, "Không xóa được bộ sưu tập"));
    } finally {
      setSaving(false);
    }
  }

  async function handleAddProduct(product: ProductPickItem) {
    // Create mode: no collection id yet, so stage the product locally and attach
    // it right after the collection is created.
    if (!selectedId) {
      if (pending.some((item) => item.id === product.id)) return;
      setPending((current) => [product, ...current]);
      setAssignMessage(`Đã chọn ${product.name}. Sản phẩm sẽ được thêm khi tạo bộ sưu tập.`);
      return;
    }

    try {
      setAssignMessage(null);
      await addProductToCollection(product.id, selectedId);
      const nextAssigned = [product, ...assigned];
      setAssigned(nextAssigned);
      assignedCacheRef.current[selectedId] = nextAssigned;
      setAssignMessage(`Đã thêm ${product.name} vào bộ sưu tập.`);
    } catch (error) {
      setAssignMessage(createAssignError(error));
    }
  }

  async function ensureCatalogRefsLoaded() {
    if (catalogLoaded) return;
    setCatalogLoaded(true);
    try {
      const [categoryList, brandList] = await Promise.all([listCategoriesForPicker(), listBrandsForPicker()]);
      setCategories(categoryList);
      setBrands(brandList);
      setNewProduct((current) => ({
        ...current,
        categoryId: current.categoryId || categoryList[0]?.id || "",
        brandId: current.brandId || brandList[0]?.id || ""
      }));
    } catch (error) {
      setCatalogLoaded(false);
      setAssignMessage(extractAdminError(error, "Không tải được danh mục / thương hiệu"));
    }
  }

  async function handleCreateProduct() {
    if (!newProduct.name.trim() || !newProduct.slug.trim()) {
      setAssignMessage("Nhập tên và slug cho sản phẩm mới.");
      return;
    }
    if (!newProduct.categoryId || !newProduct.brandId) {
      setAssignMessage("Chọn danh mục và thương hiệu cho sản phẩm mới.");
      return;
    }

    try {
      setCreatingProduct(true);
      setAssignMessage(null);
      const created = await createAdminProduct({
        name: newProduct.name.trim(),
        slug: newProduct.slug.trim(),
        shortDescription: newProduct.shortDescription.trim() || null,
        description: null,
        categoryId: newProduct.categoryId,
        brandId: newProduct.brandId,
        gender: newProduct.gender || null,
        sportType: newProduct.sportType.trim() || null,
        status: newProduct.status,
        isFeatured: false
      });
      const pickItem = productDetailToPickItem(created);
      setAllProducts((current) => [pickItem, ...current.filter((item) => item.id !== pickItem.id)]);
      await handleAddProduct(pickItem);
      setNewProduct(createEmptyProductForm());
      setNewProductSlugDirty(false);
      setAssignMessage(
        selectedId
          ? `Đã tạo sản phẩm ${created.name} và thêm vào bộ sưu tập.`
          : `Đã tạo sản phẩm ${created.name}. Sản phẩm sẽ được thêm khi tạo bộ sưu tập.`
      );
      setProductTab("existing");
    } catch (error) {
      setAssignMessage(extractAdminError(error, "Không tạo được sản phẩm mới"));
    } finally {
      setCreatingProduct(false);
    }
  }

  async function handleRemoveProduct(productId: string) {
    if (!selectedId) {
      setPending((current) => current.filter((item) => item.id !== productId));
      return;
    }

    try {
      setAssignMessage(null);
      await removeProductFromCollection(productId, selectedId);
      const nextAssigned = assigned.filter((item) => item.id !== productId);
      setAssigned(nextAssigned);
      assignedCacheRef.current[selectedId] = nextAssigned;
      setAssignMessage("Đã gỡ sản phẩm khỏi bộ sưu tập.");
    } catch (error) {
      setAssignMessage(createAssignError(error));
    }
  }

  return (
    <div className="admin-grid admin-grid-3">
      <section className="card panel">
        <div className="panel-header">
          <div>
            <h2>Danh sách bộ sưu tập</h2>
            <p className="panel-copy">Quản lý lookbook, mùa vụ và các chiến dịch trưng bày của storefront.</p>
          </div>
          <button className="admin-btn" type="button" onClick={startCreate}>
            Tạo bộ sưu tập
          </button>
        </div>

        <div className="admin-form-grid" style={{ marginBottom: 16 }}>
          <input
            className="admin-input"
            placeholder="Tìm theo tên, slug, mùa hoặc năm..."
            value={collectionSearch}
            onChange={(event) => setCollectionSearch(event.target.value)}
          />
          <select className="select" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="all">Tất cả trạng thái</option>
            {STATUS_OPTIONS.map((status) => (
              <option key={status} value={status}>
                {toStatusLabel(status)}
              </option>
            ))}
          </select>
          <select className="select" value={brandFilter} onChange={(event) => setBrandFilter(event.target.value)}>
            <option value="all">Tất cả hãng</option>
            <option value="none">Chưa gắn hãng</option>
            {brands.map((brand) => (
              <option key={brand.id} value={brand.id}>
                {brand.name}
              </option>
            ))}
          </select>
        </div>

        {filteredCollections.length === 0 ? (
          <div className="empty-state">Không có bộ sưu tập nào khớp bộ lọc hiện tại.</div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Bộ sưu tập</th>
                <th>Hãng</th>
                <th>Mùa</th>
                <th>Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {filteredCollections.map((item) => (
                <tr key={item.id} className={selectedId === item.id ? "row-selected" : ""} onClick={() => handleSelect(item)}>
                  <td>
                    <strong>{item.name}</strong>
                    <div className="table-subtle">{item.slug}</div>
                  </td>
                  <td>{item.brand?.name ?? "-"}</td>
                  <td>{[item.season, item.year].filter(Boolean).join(" ") || "-"}</td>
                  <td>
                    <span className={`status-pill status-pill-${toStatusTone(item.status)}`}>{toStatusLabel(item.status)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card panel">
        <div className="panel-header">
          <div>
            <h2>{selectedId ? "Cập nhật bộ sưu tập" : "Tạo bộ sưu tập mới"}</h2>
            <p className="panel-copy">Slug có thể nhập tay hoặc tự sinh theo tên nếu bạn chưa chỉnh thủ công.</p>
          </div>
        </div>
        {message ? <p className="action-message">{message}</p> : null}

        <div className="admin-form-grid">
          <input
            className="admin-input"
            placeholder="Tên bộ sưu tập"
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
          <select className="select" value={form.collectionType} onChange={(event) => setForm((current) => ({ ...current, collectionType: event.target.value }))}>
            {COLLECTION_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
          <select className="select" value={form.status} onChange={(event) => setForm((current) => ({ ...current, status: event.target.value }))}>
            {STATUS_OPTIONS.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
          <select
            className="select"
            value={form.brandId}
            onChange={(event) => setForm((current) => ({ ...current, brandId: event.target.value }))}
          >
            <option value="">— Không gắn hãng —</option>
            {brands.map((brand) => (
              <option key={brand.id} value={brand.id}>
                {brand.name}
              </option>
            ))}
          </select>
          <input
            className="admin-input"
            placeholder="Mùa"
            value={form.season}
            onChange={(event) => setForm((current) => ({ ...current, season: event.target.value }))}
          />
          <input
            className="admin-input"
            placeholder="Năm"
            value={form.year}
            onChange={(event) => setForm((current) => ({ ...current, year: event.target.value }))}
          />
          <input
            className="admin-input"
            placeholder="URL ảnh bìa"
            value={form.coverImageUrl}
            onChange={(event) => setForm((current) => ({ ...current, coverImageUrl: event.target.value }))}
          />
          <input
            className="admin-input"
            placeholder="URL ảnh banner"
            value={form.bannerImageUrl}
            onChange={(event) => setForm((current) => ({ ...current, bannerImageUrl: event.target.value }))}
          />
          <input
            className="admin-input"
            type="datetime-local"
            value={form.startsAt}
            onChange={(event) => setForm((current) => ({ ...current, startsAt: event.target.value }))}
          />
          <input
            className="admin-input"
            type="datetime-local"
            value={form.endsAt}
            onChange={(event) => setForm((current) => ({ ...current, endsAt: event.target.value }))}
          />
          <input
            className="admin-input"
            placeholder="Thứ tự hiển thị"
            value={form.sortOrder}
            onChange={(event) => setForm((current) => ({ ...current, sortOrder: event.target.value }))}
          />
          <label className="admin-toggle">
            <input
              type="checkbox"
              checked={form.isFeatured}
              onChange={(event) => setForm((current) => ({ ...current, isFeatured: event.target.checked }))}
            />
            <span>Đánh dấu nổi bật</span>
          </label>
          <div className="admin-form-full">
            <input
              className="admin-input"
              placeholder="Mô tả ngắn"
              value={form.shortDescription}
              onChange={(event) => setForm((current) => ({ ...current, shortDescription: event.target.value }))}
            />
          </div>
          <div className="admin-form-full">
            <textarea
              className="admin-textarea"
              placeholder="Mô tả đầy đủ"
              value={form.description}
              onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
            />
          </div>
        </div>

        <div className="page-actions">
          {selectedId && (
            <button className="admin-btn secondary" type="button" onClick={() => void handleDelete()} disabled={saving}>
              Xóa bộ sưu tập
            </button>
          )}
          <button className="admin-btn" type="button" onClick={() => void handleSubmit()} disabled={saving}>
            {saving ? "Đang lưu..." : selectedId ? "Cập nhật bộ sưu tập" : "Tạo bộ sưu tập"}
          </button>
        </div>
      </section>

      <section className="card panel">
          <div className="panel-header">
            <div>
              <h2>Gắn sản phẩm</h2>
              <p className="panel-copy">
                {selectedId
                  ? selectedCollection
                    ? `Sản phẩm đang thuộc bộ sưu tập ${selectedCollection.name}.`
                    : "Quản lý sản phẩm trong bộ sưu tập."
                  : "Chọn sản phẩm cho bộ sưu tập mới — sẽ được thêm ngay khi bạn bấm Tạo bộ sưu tập."}
              </p>
            </div>
          </div>

          <div className="editor-tabs" role="tablist" style={{ marginBottom: 16 }}>
            <button
              type="button"
              className={`editor-tab${productTab === "existing" ? " active" : ""}`}
              onClick={() => setProductTab("existing")}
            >
              Sản phẩm có sẵn
              {chosenProducts.length ? <span className="tab-count">{chosenProducts.length}</span> : null}
            </button>
            <button
              type="button"
              className={`editor-tab${productTab === "new" ? " active" : ""}`}
              onClick={() => {
                setProductTab("new");
                void ensureCatalogRefsLoaded();
              }}
            >
              Tạo sản phẩm mới
            </button>
          </div>

          {assignMessage ? <p className="action-message">{assignMessage}</p> : null}

          {productTab === "existing" ? (
          <>
          <div className="admin-form-grid" style={{ marginBottom: 16 }}>
            <input
              className="admin-input"
              placeholder="Tìm sản phẩm để thêm..."
              value={productSearch}
              onFocus={() => void ensureProductPickerLoaded()}
              onChange={(event) => {
                void ensureProductPickerLoaded();
                setProductSearch(event.target.value);
              }}
            />
          </div>

          <div style={{ marginBottom: 8 }}>
            <h3 style={{ fontSize: 14, fontWeight: 700, marginBottom: 12 }}>
              {selectedId
                ? `Sản phẩm đang thuộc bộ sưu tập (${chosenProducts.length})`
                : `Sản phẩm đã chọn (${chosenProducts.length})`}
            </h3>
            {loadingProducts && chosenProducts.length === 0 ? (
              <div className="empty-state">Đang tải dữ liệu sản phẩm...</div>
            ) : chosenProducts.length === 0 ? (
              <div className="empty-state">
                {selectedId ? "Bộ sưu tập này chưa có sản phẩm nào." : "Chưa chọn sản phẩm nào. Tìm và thêm ở danh sách bên dưới."}
              </div>
            ) : (
              <div className="admin-gallery">
                {chosenProducts.map((product) => {
                  const brandName = getProductBrandName(product);
                  const categoryName = getProductCategoryName(product);
                  const subtitle = [brandName, categoryName].filter(Boolean).join(" · ");
                  return (
                    <article key={product.id} className="admin-image-card">
                      <Image
                        src={product.thumbnail ?? PLACEHOLDER_IMG}
                        alt={product.name}
                        width={96}
                        height={96}
                        style={{ width: 96, height: 96, borderRadius: 12, objectFit: "cover" }}
                        unoptimized
                      />
                      <div>
                        <strong style={{ display: "block", marginBottom: 2 }}>{product.name}</strong>
                        {subtitle ? <div className="table-subtle">{subtitle}</div> : null}
                        <div className="table-subtle">{product.slug}</div>
                        <span className={`status ${productStatusClass(product.status)}`} style={{ marginTop: 4, display: "inline-block" }}>
                          {product.status}
                        </span>
                      </div>
                      <button
                        className="admin-btn secondary"
                        type="button"
                        onClick={() => void handleRemoveProduct(product.id)}
                      >
                        {selectedId ? "Gỡ khỏi bộ sưu tập" : "Bỏ chọn"}
                      </button>
                    </article>
                  );
                })}
              </div>
            )}
          </div>

          <div style={{ marginTop: 24 }}>
            <h3 style={{ fontSize: 14, fontWeight: 700, marginBottom: 12 }}>Sản phẩm khả dụng để thêm</h3>
            {loadingProducts && allProducts.length === 0 ? (
              <div className="empty-state">Đang tải danh mục sản phẩm...</div>
            ) : filteredProducts.length === 0 ? (
              <div className="empty-state">
                {allProducts.length === 0
                  ? "Chưa có sản phẩm nào sẵn sàng để thêm hoặc bạn đã thêm hết trong bộ sưu tập này."
                  : "Không tìm thấy sản phẩm phù hợp."}
              </div>
            ) : (
              <div className="admin-gallery">
                {filteredProducts.map((product) => {
                  const brandName = getProductBrandName(product);
                  const categoryName = getProductCategoryName(product);
                  const subtitle = [brandName, categoryName].filter(Boolean).join(" · ");
                  return (
                    <article key={product.id} className="admin-image-card">
                      <Image
                        src={product.thumbnail ?? PLACEHOLDER_IMG}
                        alt={product.name}
                        width={96}
                        height={96}
                        style={{ width: 96, height: 96, borderRadius: 12, objectFit: "cover" }}
                        unoptimized
                      />
                      <div>
                        <strong style={{ display: "block", marginBottom: 2 }}>{product.name}</strong>
                        {subtitle ? <div className="table-subtle">{subtitle}</div> : null}
                        <div className="table-subtle">{product.slug}</div>
                        <span className={`status ${productStatusClass(product.status)}`} style={{ marginTop: 4, display: "inline-block" }}>
                          {product.status}
                        </span>
                      </div>
                      <button
                        className="admin-btn"
                        type="button"
                        onClick={() => void handleAddProduct(product)}
                      >
                        Thêm vào
                      </button>
                    </article>
                  );
                })}
              </div>
            )}
          </div>
          </>
          ) : (
            <div className="admin-form-grid">
              {categories.length === 0 || brands.length === 0 ? (
                <div className="admin-form-full">
                  <div className="empty-state">
                    {!catalogLoaded
                      ? "Đang tải danh mục và thương hiệu..."
                      : "Cần có ít nhất 1 danh mục và 1 thương hiệu để tạo sản phẩm. Hãy tạo ở trang tương ứng trước."}
                  </div>
                </div>
              ) : null}
              <input
                className="admin-input"
                placeholder="Tên sản phẩm"
                value={newProduct.name}
                onChange={(event) => {
                  const name = event.target.value;
                  setNewProduct((current) => ({ ...current, name, ...(!newProductSlugDirty && { slug: toSlug(name) }) }));
                }}
              />
              <input
                className="admin-input"
                placeholder="Slug"
                value={newProduct.slug}
                onChange={(event) => {
                  setNewProductSlugDirty(true);
                  setNewProduct((current) => ({ ...current, slug: event.target.value }));
                }}
              />
              <select
                className="select"
                value={newProduct.categoryId}
                onChange={(event) => setNewProduct((current) => ({ ...current, categoryId: event.target.value }))}
              >
                <option value="">-- Danh mục --</option>
                {categories.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
              <select
                className="select"
                value={newProduct.brandId}
                onChange={(event) => setNewProduct((current) => ({ ...current, brandId: event.target.value }))}
              >
                <option value="">-- Thương hiệu --</option>
                {brands.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
              <select
                className="select"
                value={newProduct.gender}
                onChange={(event) => setNewProduct((current) => ({ ...current, gender: event.target.value }))}
              >
                <option value="">Không chọn giới tính</option>
                {PRODUCT_GENDER_OPTIONS.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
              <select
                className="select"
                value={newProduct.status}
                onChange={(event) => setNewProduct((current) => ({ ...current, status: event.target.value }))}
              >
                {PRODUCT_STATUS_OPTIONS.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
              <div className="admin-form-full">
                <input
                  className="admin-input"
                  placeholder="Môn thể thao (tùy chọn)"
                  value={newProduct.sportType}
                  onChange={(event) => setNewProduct((current) => ({ ...current, sportType: event.target.value }))}
                />
              </div>
              <div className="admin-form-full">
                <input
                  className="admin-input"
                  placeholder="Mô tả ngắn (tùy chọn)"
                  value={newProduct.shortDescription}
                  onChange={(event) => setNewProduct((current) => ({ ...current, shortDescription: event.target.value }))}
                />
              </div>
              <div className="admin-form-full">
                <p className="table-subtle" style={{ marginBottom: 12 }}>
                  Sản phẩm mới sẽ được tạo (dạng nháp) và thêm ngay vào bộ sưu tập. Biến thể, ảnh có thể bổ sung sau ở trang Sản phẩm.
                </p>
                <div className="page-actions">
                  <button
                    className="admin-btn"
                    type="button"
                    onClick={() => void handleCreateProduct()}
                    disabled={creatingProduct || categories.length === 0 || brands.length === 0}
                  >
                    {creatingProduct ? "Đang tạo..." : selectedId ? "Tạo & thêm vào bộ sưu tập" : "Tạo & chọn sản phẩm"}
                  </button>
                </div>
              </div>
            </div>
          )}
        </section>
    </div>
  );
}
﻿
