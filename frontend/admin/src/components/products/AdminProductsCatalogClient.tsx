
"use client";

import Image from "next/image";
import { useRouter } from "next/navigation";
import { memo, useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import { ApiRequestError } from "@/modules/api/common";
import { NO_IMAGE } from "@/modules/ui/placeholder";
import { normalizeImageUrl } from "@/modules/ui/image-url";
import { toSlug } from "@/modules/utils/slug";
import {
  addProductImage,
  createAdminProduct,
  createVariant,
  deleteProduct,
  deleteProductImage,
  setPrimaryProductImage,
  fetchAdminProductDetail,
  updateAdminProduct,
  updateVariant,
  uploadProductImage
} from "@/modules/catalog-admin/browser-api";
import type {
  BrandResponse,
  CategoryResponse,
  ProductDetailResponse,
  ProductImageResponse,
  ProductVariantResponse
} from "@/modules/catalog-admin/types";
import type { AdminProduct } from "@/modules/product-management/products";

const PRODUCTS_PER_PAGE = 15;
const PRODUCT_THUMB_FALLBACK = NO_IMAGE;
const PRODUCT_IMAGE_IDEAL_WIDTH = 1200;
const PRODUCT_IMAGE_IDEAL_HEIGHT = 1800;
const PRODUCT_IMAGE_MIN_WIDTH = 800;
const PRODUCT_IMAGE_MIN_HEIGHT = 1200;
const PRODUCT_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
const PRODUCT_IMAGE_MAX_BATCH = 10;
const PRODUCT_IMAGE_MIME_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);
const productStatusOptions = ["DRAFT", "ACTIVE", "INACTIVE"] as const;
const genderOptions = ["MEN", "WOMEN", "UNISEX"] as const;
const productTypeOptions = ["SPORTS_TOP", "SPORTS_BOTTOM", "SPORTS_SHOES", "SPORTS_UNIFORM", "SPORTS_ACCESSORY"] as const;
const dataSourceOptions = ["REAL", "DEMO", "SYNTHETIC"] as const;
const sportOptions = ["FOOTBALL", "RUNNING"] as const;
const variantStatusOptions = ["ACTIVE", "OUT_OF_STOCK", "INACTIVE"] as const;
const quickSizeOptions = ["S", "M", "L", "XL", "XXL", "39", "40", "41", "42", "43"];

type ApiFieldErrorPayload = {
  field?: string;
  message?: string;
};

type ApiEnvelopePayload = {
  message?: string;
  data?: ApiFieldErrorPayload[] | null;
  errors?: ApiFieldErrorPayload[] | null;
};

type VariantDraft = ReturnType<typeof createEmptyVariantForm>;

// One auto-generated row in the "create many variants" matrix (color × size).
type GenVariantRow = {
  key: string;
  color: string;
  size: string;
  sku: string;
  price: string;
  compareAtPrice: string;
  stockQuantity: string;
};

// "All Black, Red , Red" -> ["All Black", "Red"] (trimmed, de-duped, blanks dropped).
function parseCsvList(input: string) {
  return Array.from(new Set(input.split(",").map((item) => item.trim()).filter(Boolean)));
}

// Turn a human label into an uppercase SKU token: "All Black" -> "ALLBLACK".
function skuToken(value: string) {
  return toSlug(value).replace(/-/g, "").toUpperCase();
}

function buildUniqueVariantSku(baseValue: string, color: string, size: string, existingSkus: string[]) {
  const base = skuToken(baseValue) || "SP";
  const root = [base, skuToken(color), skuToken(size)].filter(Boolean).join("-").slice(0, 100);
  const occupied = new Set(existingSkus.map((sku) => sku.trim().toUpperCase()));
  if (!occupied.has(root)) return root;

  // Preserve the readable base/color/size structure and add a deterministic
  // sequence only when that SKU is already occupied on the current product.
  for (let sequence = 2; sequence <= 999; sequence += 1) {
    const suffix = `-${String(sequence).padStart(2, "0")}`;
    const candidate = `${root.slice(0, 100 - suffix.length)}${suffix}`;
    if (!occupied.has(candidate)) return candidate;
  }
  return root;
}

const ADMIN_SIZE_ORDER = ["XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "2XL", "3XL", "4XL"];
function adminSizeRank(label: string | null | undefined) {
  const idx = ADMIN_SIZE_ORDER.indexOf((label ?? "").trim().toUpperCase());
  if (idx !== -1) return idx;
  const num = Number(label);
  return Number.isFinite(num) ? 100 + num : 999;
}

// Group a product's variants by color so the editor shows one block per color
// (color once at the top) with its sizes listed inside, naturally ordered.
function groupVariantsByColor(variants: ProductVariantResponse[]): Array<[string, ProductVariantResponse[]]> {
  const groups = new Map<string, ProductVariantResponse[]>();
  for (const variant of variants) {
    const key = variant.color ?? "";
    const arr = groups.get(key) ?? [];
    arr.push(variant);
    groups.set(key, arr);
  }
  return Array.from(groups.entries()).map(
    ([color, list]) =>
      [color, [...list].sort((a, b) => adminSizeRank(a.size) - adminSizeRank(b.size))] as [string, ProductVariantResponse[]],
  );
}

const fieldLabelMap: Record<string, string> = {
  name: "Tên sản phẩm",
  slug: "Slug",
  categoryId: "Danh mục",
  brandId: "Thương hiệu",
  shortDescription: "Mô tả ngắn",
  description: "Mô tả chi tiết",
  gender: "Giới tính",
  sportType: "Môn thể thao",
  productType: "Loại sản phẩm",
  dataSource: "Nguồn dữ liệu",
  status: "Trạng thái",
  sku: "SKU",
  size: "Kích cỡ",
  color: "Màu sắc",
  price: "Giá bán",
  compareAtPrice: "Giá so sánh",
  stockQuantity: "Tồn kho"
};

function formatFieldErrors(items: ApiFieldErrorPayload[]) {
  const lines = items
    .map((item) => {
      const label = item.field ? fieldLabelMap[item.field] ?? item.field : null;
      const message = item.message?.trim();

      if (!message) {
        return null;
      }

      return label ? `${label}: ${message}` : message;
    })
    .filter((value): value is string => Boolean(value));

  return lines.length > 0 ? lines.join(" | ") : null;
}

function extractError(error: unknown, fallback: string) {
  if (error instanceof ApiRequestError) {
    const payload = error.payload as ApiEnvelopePayload | null;
    const fieldErrors = [
      ...(Array.isArray(payload?.errors) ? payload.errors : []),
      ...(Array.isArray(payload?.data) ? payload.data : [])
    ];
    const fieldMessage = formatFieldErrors(fieldErrors);

    if (fieldMessage) {
      return fieldMessage;
    }

    return payload?.message ?? fallback;
  }

  return fallback;
}

function formatPrice(value: string) {
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount <= 0) {
    return "Liên hệ";
  }

  return `${Math.round(amount).toLocaleString("vi-VN")} ₫`;
}

function mapAdminStatus(status: ProductDetailResponse["status"], stock: number): AdminProduct["status"] {
  if (status !== "ACTIVE") {
    return "draft";
  }

  if (stock <= 0) {
    return "out";
  }

  if (stock <= 10) {
    return "low";
  }

  return "active";
}

function getPublishChecklist(detail: ProductDetailResponse | null, form: ReturnType<typeof createEmptyProductForm>) {
  const activeVariants = detail?.variants.filter((variant) => variant.status === "ACTIVE") ?? [];
  const pricedVariants = activeVariants.filter((variant) => Number(variant.price) > 0);
  const primaryImage = detail?.images.find((image) => image.isPrimary) ?? detail?.images[0] ?? null;

  return [
    {
      key: "info",
      label: "Thông tin cơ bản",
      help: "Tên, slug, danh mục và thương hiệu phải đầy đủ.",
      done: Boolean(form.name.trim() && form.slug.trim() && form.categoryId && form.brandId)
    },
    {
      key: "variants",
      label: "Biến thể bán hàng",
      help: "Cần ít nhất một biến thể ACTIVE.",
      done: activeVariants.length > 0
    },
    {
      key: "prices",
      label: "Giá bán hợp lệ",
      help: "Biến thể đang bán phải có giá > 0.",
      done: activeVariants.length > 0 && pricedVariants.length === activeVariants.length
    },
    {
      key: "images",
      label: "Ảnh sản phẩm",
      help: "Cần ít nhất một ảnh, tốt nhất có ảnh chính.",
      done: Boolean(primaryImage)
    }
  ];
}

function getPublishBlockReason(checklist: ReturnType<typeof getPublishChecklist>) {
  const missing = checklist.filter((item) => !item.done).map((item) => item.label.toLowerCase());
  return missing.length ? `Chưa thể chuyển ACTIVE: còn thiếu ${missing.join(", ")}.` : null;
}

function createEmptyProductForm(categoryId = "", brandId = "") {
  return {
    name: "",
    slug: "",
    shortDescription: "",
    description: "",
    categoryId,
    brandId,
    gender: "",
    sportType: "",
    productType: "SPORTS_TOP",
    dataSource: "REAL",
    status: "DRAFT",
    isFeatured: false
  };
}

function createEmptyVariantForm() {
  return {
    sku: "",
    size: "",
    color: "",
    price: "",
    compareAtPrice: "",
    stockQuantity: "",
    status: "ACTIVE"
  };
}

function createEmptyImageForm() {
  return {
    imageUrl: "",
    altText: "",
    color: "",
    isPrimary: false,
    sortOrder: ""
  };
}

function createEmptyUploadForm() {
  return {
    files: [] as Array<{
      id: string;
      file: File;
      width: number;
      height: number;
      orderOffset: number;
      warning: string | null;
      status: "pending" | "uploading" | "failed";
      error: string | null;
    }>,
    altText: "",
    color: "",
    isPrimary: false,
    sortOrder: ""
  };
}

function toAdminProduct(detail: ProductDetailResponse, categories: CategoryResponse[], brands: BrandResponse[]): AdminProduct {
  const stock = detail.variants.reduce((sum, item) => sum + item.availableQuantity, 0);
  const prices = detail.variants.map((item) => item.price).filter((value): value is number => value != null);
  const minPrice = prices.length ? Math.min(...prices) : 0;
  const maxPrice = prices.length ? Math.max(...prices) : 0;
  const firstVariant = detail.variants[0];

  return {
    id: detail.id,
    sku: firstVariant?.sku ?? detail.slug,
    name: detail.name,
    category: categories.find((item) => item.id === detail.category?.id)?.name ?? detail.category?.name ?? "Chưa phân loại",
    brand: brands.find((item) => item.id === detail.brand?.id)?.name ?? detail.brand?.name ?? "Chưa có thương hiệu",
    price: minPrice === maxPrice ? formatPrice(String(minPrice)) : `${formatPrice(String(minPrice))} - ${formatPrice(String(maxPrice))}`,
    stock,
    sold: 0,
    status: mapAdminStatus(detail.status, stock),
    isFeatured: detail.isFeatured,
    image: detail.images[0]?.imageUrl ?? NO_IMAGE
  };
}

function buildVariantDrafts(variants: ProductVariantResponse[]) {
  return Object.fromEntries(
    variants.map((item) => [
      item.id,
      {
        sku: item.sku,
        size: item.size ?? "",
        color: item.color ?? "",
        price: item.price != null ? String(item.price) : "",
        compareAtPrice: item.compareAtPrice != null ? String(item.compareAtPrice) : "",
        stockQuantity: String(item.availableQuantity),
        status: item.status
      }
    ])
  );
}

function buildProductForm(detail: ProductDetailResponse, categories: CategoryResponse[], brands: BrandResponse[]) {
  return {
    name: detail.name,
    slug: detail.slug,
    shortDescription: detail.shortDescription ?? "",
    description: detail.description ?? "",
    categoryId: detail.category?.id ?? categories[0]?.id ?? "",
    brandId: detail.brand?.id ?? brands[0]?.id ?? "",
    gender: detail.gender ?? "",
    sportType: detail.sportType ?? "",
    productType: detail.productType ?? "SPORTS_TOP",
    dataSource: "REAL",
    status: detail.status,
    isFeatured: detail.isFeatured
  };
}
const VariantEditorCard = memo(function VariantEditorCard({
  variant,
  draft,
  saving,
  onDraftChange,
  onSave
}: {
  variant: ProductVariantResponse;
  draft: VariantDraft;
  saving: boolean;
  onDraftChange: (patch: Partial<VariantDraft>) => void;
  onSave: () => void;
}) {
  return (
    <tr title={variant.sku}>
      <td>
        <input className="admin-input" value={draft.size} onChange={(event) => onDraftChange({ size: event.target.value })} />
      </td>
      <td>
        <input className="admin-input" type="number" min={0} value={draft.price} onChange={(event) => onDraftChange({ price: event.target.value })} />
      </td>
      <td>
        <input className="admin-input" type="number" min={0} value={draft.compareAtPrice} onChange={(event) => onDraftChange({ compareAtPrice: event.target.value })} />
      </td>
      <td>
        <select className="select" value={draft.status} onChange={(event) => onDraftChange({ status: event.target.value })}>
          {variantStatusOptions.map((item) => (
            <option value={item} key={item}>
              {item}
            </option>
          ))}
        </select>
      </td>
      <td className="variant-stock">{variant.availableQuantity}</td>
      <td>
        <button className="admin-btn secondary" type="button" onClick={onSave} disabled={saving}>
          {saving ? "..." : "Lưu"}
        </button>
      </td>
    </tr>
  );
});

const ProductImageCard = memo(function ProductImageCard({
  image,
  productName,
  saving,
  settingPrimary,
  onDelete,
  onSetPrimary
}: {
  image: ProductImageResponse;
  productName: string;
  saving: boolean;
  settingPrimary: boolean;
  onDelete: () => void;
  onSetPrimary: () => void;
}) {
  return (
    <article className={`admin-image-card${image.isPrimary ? " is-primary" : ""}`}>
      <Image
        src={normalizeImageUrl(image.imageUrl, NO_IMAGE)}
        alt={image.altText ?? productName}
        width={320}
        height={320}
        sizes="(max-width: 768px) 100vw, 320px"
        style={{ width: "100%", height: "auto" }}
        unoptimized
      />
      <div>
        <strong>{image.altText ?? "Không có alt text"}</strong>
        <div className="table-subtle">
          {image.color ? `Màu: ${image.color} · ` : ""}
          {image.isPrimary ? "Ảnh chính (nổi bật)" : "Ảnh phụ"} · Thứ tự {image.sortOrder}
        </div>
      </div>
      <div className="admin-image-actions">
        {image.isPrimary ? (
          <span className="status active">Ảnh nổi bật</span>
        ) : (
          <button
            className="admin-btn secondary"
            type="button"
            onClick={onSetPrimary}
            disabled={settingPrimary || saving}
          >
            {settingPrimary ? "Đang đặt..." : "Đặt làm ảnh nổi bật"}
          </button>
        )}
        <button className="admin-btn secondary" type="button" onClick={onDelete} disabled={saving || settingPrimary}>
          {saving ? "Đang xóa..." : "Xóa ảnh"}
        </button>
      </div>
    </article>
  );
});

export function AdminProductsCatalogClient({
  initialProducts,
  initialSearchTerm = "",
  categories,
  brands
}: {
  initialProducts: AdminProduct[];
  initialSearchTerm?: string;
  categories: CategoryResponse[];
  brands: BrandResponse[];
}) {
  const router = useRouter();
  const [products, setProducts] = useState(initialProducts);
  const [selectedProductId, setSelectedProductId] = useState(initialProducts.find((item) => item.id)?.id ?? "");
  const [detail, setDetail] = useState<ProductDetailResponse | null>(null);
  const detailCacheRef = useRef<Record<string, ProductDetailResponse>>({});
  const [detailLoading, setDetailLoading] = useState(false);
  const [productForm, setProductForm] = useState(createEmptyProductForm(categories[0]?.id ?? "", brands[0]?.id ?? ""));
  const [slugDirty, setSlugDirty] = useState(false);
  const [variantForm, setVariantForm] = useState(createEmptyVariantForm());
  const [variantSkuDirty, setVariantSkuDirty] = useState(false);
  const [variantDrafts, setVariantDrafts] = useState<Record<string, VariantDraft>>({});
  // "Create many variants" matrix state.
  const [genColors, setGenColors] = useState("");
  const [genSizes, setGenSizes] = useState("");
  const [genBaseSku, setGenBaseSku] = useState("");
  const [bulkPrice, setBulkPrice] = useState("");
  const [bulkCompare, setBulkCompare] = useState("");
  const [bulkStock, setBulkStock] = useState("0");
  const [genRows, setGenRows] = useState<GenVariantRow[]>([]);
  const [imageForm, setImageForm] = useState(createEmptyImageForm());
  const [uploadForm, setUploadForm] = useState(createEmptyUploadForm());
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState(initialSearchTerm);
  const [statusFilter, setStatusFilter] = useState<"all" | AdminProduct["status"] | "featured">("all");
  const [activeTab, setActiveTab] = useState<"info" | "variants" | "images">("info");
  const [page, setPage] = useState(1);
  const deferredSearchTerm = useDeferredValue(searchTerm);

  useEffect(() => {
    setSearchTerm(initialSearchTerm);
  }, [initialSearchTerm]);

  const selectedProduct = useMemo(() => products.find((item) => item.id === selectedProductId) ?? null, [products, selectedProductId]);
  const selectedProductImage = useMemo(() => {
    if (detail?.id === selectedProductId) {
      const primaryImage = detail.images.find((image) => image.isPrimary) ?? detail.images[0];
      if (primaryImage?.imageUrl) {
        return primaryImage.imageUrl;
      }
    }

    return selectedProduct?.image ?? PRODUCT_THUMB_FALLBACK;
  }, [detail, selectedProduct, selectedProductId]);
  const publishChecklist = useMemo(() => getPublishChecklist(detail, productForm), [detail, productForm]);
  const publishBlockReason = useMemo(() => getPublishBlockReason(publishChecklist), [publishChecklist]);
  const canPublishActive = !publishBlockReason;
  const parsedGenColors = useMemo(() => parseCsvList(genColors), [genColors]);
  const parsedGenSizes = useMemo(() => parseCsvList(genSizes), [genSizes]);
  const missingCategorySetup = categories.length === 0;
  const missingBrandSetup = brands.length === 0;
  const canSubmitProduct = !missingCategorySetup && !missingBrandSetup && saving !== "product";
  const filteredProducts = useMemo(() => {
    const keyword = deferredSearchTerm.trim().toLowerCase();

    return products.filter((product) => {
      const matchesStatus =
        statusFilter === "all"
          ? true
          : statusFilter === "featured"
            ? Boolean(product.isFeatured)
            : product.status === statusFilter;
      if (!matchesStatus) {
        return false;
      }

      if (!keyword) {
        return true;
      }

      const haystack = [product.name, product.sku, product.category, product.brand].join(" ").toLowerCase();
      return haystack.includes(keyword);
    });
  }, [deferredSearchTerm, products, statusFilter]);

  const totalPages = Math.max(1, Math.ceil(filteredProducts.length / PRODUCTS_PER_PAGE));
  const currentPage = Math.min(page, totalPages);
  const pagedProducts = useMemo(
    () => filteredProducts.slice((currentPage - 1) * PRODUCTS_PER_PAGE, currentPage * PRODUCTS_PER_PAGE),
    [filteredProducts, currentPage]
  );

  // Reset to the first page whenever the filter/search result set changes.
  useEffect(() => {
    setPage(1);
  }, [deferredSearchTerm, statusFilter]);

  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => setMessage(null), 4000);
    return () => clearTimeout(timer);
  }, [message]);

  useEffect(() => {
    if (!selectedProductId) {
      setDetail(null);
      return;
    }

    let active = true;

    async function loadDetail() {
      try {
        setDetailLoading(true);
        const cached = detailCacheRef.current[selectedProductId];
        if (cached) {
          setDetail(cached);
          setSlugDirty(true);
          setProductForm(buildProductForm(cached, categories, brands));
          setVariantDrafts(buildVariantDrafts(cached.variants));
          return;
        }

        const response = await fetchAdminProductDetail(selectedProductId);
        if (!active) {
          return;
        }

        detailCacheRef.current[response.id] = response;
        setDetail(response);
        setSlugDirty(true);
        setProductForm(buildProductForm(response, categories, brands));
        setVariantDrafts(buildVariantDrafts(response.variants));
      } catch (error) {
        if (active) {
          setMessage(extractError(error, "Không tải được chi tiết sản phẩm"));
        }
      } finally {
        if (active) {
          setDetailLoading(false);
        }
      }
    }

    void loadDetail();

    return () => {
      active = false;
    };
  }, [brands, categories, selectedProductId]);

  useEffect(() => {
    setVariantForm(createEmptyVariantForm());
    setVariantSkuDirty(false);
  }, [selectedProductId]);

  function upsertProductCard(nextDetail: ProductDetailResponse) {
    const nextProduct = toAdminProduct(nextDetail, categories, brands);
    setProducts((current) => {
      const existingIndex = current.findIndex((item) => item.id === nextProduct.id);
      if (existingIndex === -1) {
        return [nextProduct, ...current];
      }

      const clone = current.slice();
      clone[existingIndex] = { ...clone[existingIndex], ...nextProduct, sold: clone[existingIndex].sold };
      return clone;
    });
  }

  async function refreshDetail(productId: string) {
    const response = await fetchAdminProductDetail(productId);
    detailCacheRef.current[response.id] = response;
    setDetail(response);
    upsertProductCard(response);
    setVariantDrafts(buildVariantDrafts(response.variants));
    return response;
  }
  async function handleCreateOrUpdateProduct() {
    if (missingCategorySetup) {
      setMessage("Chưa có danh mục nào trong hệ thống. Hãy tạo danh mục trước khi tạo sản phẩm.");
      return;
    }

    if (missingBrandSetup) {
      setMessage("Chưa có thương hiệu nào trong hệ thống. Hãy tạo thương hiệu trước khi tạo sản phẩm.");
      return;
    }

    try {
      setSaving("product");
      setMessage(null);

      const payload = {
        name: productForm.name,
        slug: productForm.slug,
        shortDescription: productForm.shortDescription || null,
        description: productForm.description || null,
        categoryId: productForm.categoryId,
        brandId: productForm.brandId,
        gender: productForm.gender || null,
        sportType: productForm.sportType || null,
        productType: productForm.productType,
        status: productForm.status,
        isFeatured: productForm.isFeatured
      };

      if (payload.status === "ACTIVE" && publishBlockReason) {
        setMessage(publishBlockReason);
        return;
      }

      if (selectedProductId) {
        const updated = await updateAdminProduct(selectedProductId, payload);
        detailCacheRef.current[updated.id] = updated;
        setDetail(updated);
        upsertProductCard(updated);
        setMessage(`Đã cập nhật sản phẩm ${updated.name}.`);
        router.refresh();
        return;
      }

      const created = await createAdminProduct({ ...payload, dataSource: productForm.dataSource });
      detailCacheRef.current[created.id] = created;
      setSelectedProductId(created.id);
      setDetail(created);
      upsertProductCard(created);
      setMessage(`Đã tạo sản phẩm ${created.name}.`);
      router.refresh();
    } catch (error) {
      setMessage(extractError(error, "Không lưu được sản phẩm"));
    } finally {
      setSaving(null);
    }
  }

  async function handleDeleteProduct() {
    if (!selectedProductId) return;
    if (!window.confirm("Xóa sản phẩm này? Hành động không thể hoàn tác.")) return;
    const idToDelete = selectedProductId;
    const nextId = products.find((item) => item.id && item.id !== idToDelete)?.id ?? "";
    try {
      setSaving("product-delete");
      await deleteProduct(idToDelete);
      delete detailCacheRef.current[idToDelete];
      setProducts((prev) => prev.filter((item) => item.id !== idToDelete));
      setSelectedProductId(nextId);
      setDetail(null);
      setMessage("Đã xóa sản phẩm.");
      router.refresh();
    } catch (error) {
      setMessage(extractError(error, "Không xóa được sản phẩm"));
    } finally {
      setSaving(null);
    }
  }

  function handleGenerateMatrix() {
    const colors = parseCsvList(genColors);
    const sizes = parseCsvList(genSizes);
    const colorList = colors.length ? colors : [""];
    const sizeList = sizes.length ? sizes : [""];

    if (colorList.length === 1 && colorList[0] === "" && sizeList.length === 1 && sizeList[0] === "") {
      setMessage("Hãy nhập ít nhất một size hoặc một màu để tạo bảng biến thể.");
      return;
    }

    const base = (genBaseSku.trim() ? skuToken(genBaseSku) : skuToken(detail?.slug ?? productForm.slug ?? productForm.name ?? "SP")) || "SP";
    // Skip color/size pairs that already exist on this product.
    const existing = new Set((detail?.variants ?? []).map((v) => `${(v.color ?? "").toLowerCase()}|${(v.size ?? "").toLowerCase()}`));

    const rows: GenVariantRow[] = [];
    for (const color of colorList) {
      for (const size of sizeList) {
        if (existing.has(`${color.toLowerCase()}|${size.toLowerCase()}`)) continue;
        const skuParts = [base, color ? skuToken(color) : "", size ? skuToken(size) : ""].filter(Boolean);
        rows.push({
          key: `${color}__${size}`,
          color,
          size,
          sku: skuParts.join("-"),
          price: bulkPrice,
          compareAtPrice: bulkCompare,
          stockQuantity: bulkStock || "0"
        });
      }
    }

    if (rows.length === 0) {
      setGenRows([]);
      setMessage("Tất cả tổ hợp màu/size này đã tồn tại — không có biến thể mới để tạo.");
      return;
    }

    setGenRows(rows);
    setMessage(`Đã tạo bảng ${rows.length} biến thể. Điền giá/tồn rồi bấm "Lưu tất cả".`);
  }

  function applyBulkToRows() {
    setGenRows((rows) =>
      rows.map((row) => ({
        ...row,
        price: bulkPrice !== "" ? bulkPrice : row.price,
        compareAtPrice: bulkCompare !== "" ? bulkCompare : row.compareAtPrice,
        stockQuantity: bulkStock !== "" ? bulkStock : row.stockQuantity
      }))
    );
  }

  function updateGenRow(key: string, patch: Partial<GenVariantRow>) {
    setGenRows((rows) => rows.map((row) => (row.key === key ? { ...row, ...patch } : row)));
  }

  function removeGenRow(key: string) {
    setGenRows((rows) => rows.filter((row) => row.key !== key));
  }

  async function handleSaveAllVariants() {
    if (!selectedProductId || genRows.length === 0) return;

    if (genRows.some((row) => !(Number(row.price) > 0))) {
      setMessage('Mỗi biến thể cần giá bán > 0. Dùng "Áp dụng cho tất cả" để điền nhanh.');
      return;
    }

    try {
      setSaving("variant-bulk");
      setMessage(null);
      let created = 0;
      const failed: string[] = [];

      for (const row of genRows) {
        try {
          await createVariant(selectedProductId, {
            sku: row.sku,
            size: row.size,
            color: row.color,
            price: Number(row.price),
            compareAtPrice: row.compareAtPrice ? Number(row.compareAtPrice) : null,
            stockQuantity: Number(row.stockQuantity || 0),
            status: "ACTIVE"
          });
          created += 1;
        } catch (error) {
          failed.push(`${row.sku}: ${extractError(error, "lỗi")}`);
        }
      }

      await refreshDetail(selectedProductId);
      setGenRows([]);
      setGenColors("");
      setGenSizes("");
      setMessage(
        failed.length
          ? `Đã tạo ${created} biến thể. ${failed.length} lỗi: ${failed.slice(0, 3).join(" | ")}`
          : `Đã tạo ${created} biến thể mới.`
      );
    } finally {
      setSaving(null);
    }
  }

  async function handleCreateVariant() {
    if (!selectedProductId) {
      setMessage("Hãy tạo sản phẩm trước khi thêm biến thể.");
      return;
    }

    try {
      setSaving("variant-create");
      setMessage(null);
      const generatedSku = buildUniqueVariantSku(
        detail?.slug ?? productForm.slug ?? productForm.name,
        variantForm.color,
        variantForm.size,
        detail?.variants.map((variant) => variant.sku) ?? []
      );
      await createVariant(selectedProductId, {
        sku: variantForm.sku.trim() || generatedSku,
        size: variantForm.size,
        color: variantForm.color,
        price: Number(variantForm.price),
        compareAtPrice: variantForm.compareAtPrice ? Number(variantForm.compareAtPrice) : null,
        stockQuantity: Number(variantForm.stockQuantity || 0),
        status: variantForm.status
      });
      await refreshDetail(selectedProductId);
      setVariantForm(createEmptyVariantForm());
      setVariantSkuDirty(false);
      setMessage("Đã thêm biến thể mới.");
    } catch (error) {
      setMessage(extractError(error, "Không thêm được biến thể"));
    } finally {
      setSaving(null);
    }
  }

  async function handleUpdateVariant(variantId: string) {
    const draft = variantDrafts[variantId];
    if (!draft) {
      return;
    }

    try {
      setSaving(`variant-${variantId}`);
      setMessage(null);
      const updated = await updateVariant(variantId, {
        size: draft.size,
        color: draft.color,
        price: Number(draft.price),
        compareAtPrice: draft.compareAtPrice ? Number(draft.compareAtPrice) : null,
        status: draft.status
      });

      setVariantDrafts((current) => ({
        ...current,
        [variantId]: {
          ...current[variantId],
          size: updated.size ?? "",
          color: updated.color ?? "",
          price: updated.price != null ? String(updated.price) : "",
          compareAtPrice: updated.compareAtPrice != null ? String(updated.compareAtPrice) : "",
          status: updated.status,
          stockQuantity: String(updated.availableQuantity),
          sku: updated.sku
        }
      }));

      if (selectedProductId) {
        await refreshDetail(selectedProductId);
      }
      setMessage(`Đã cập nhật biến thể ${updated.sku}.`);
    } catch (error) {
      setMessage(extractError(error, "Không cập nhật được biến thể"));
    } finally {
      setSaving(null);
    }
  }

  async function handleAddImageByUrl() {
    if (!selectedProductId) {
      setMessage("Hãy tạo sản phẩm trước khi thêm ảnh.");
      return;
    }

    try {
      setSaving("image-url");
      setMessage(null);
      await addProductImage(selectedProductId, {
        imageUrl: normalizeImageUrl(imageForm.imageUrl, ""),
        // A pasted URL is not necessarily managed by our Cloudinary account.
        // Only the upload endpoint should persist a storage-provider publicId.
        publicId: null,
        altText: imageForm.altText || null,
        color: imageForm.color.trim() || null,
        sortOrder: Number(imageForm.sortOrder || 0),
        isPrimary: imageForm.isPrimary
      });
      await refreshDetail(selectedProductId);
      setImageForm(createEmptyImageForm());
      setMessage("Đã thêm ảnh bằng URL.");
    } catch (error) {
      setMessage(extractError(error, "Không thêm được ảnh"));
    } finally {
      setSaving(null);
    }
  }

  async function handleUploadImage() {
    if (!selectedProductId) {
      setMessage("Hãy tạo sản phẩm trước khi upload ảnh.");
      return;
    }

    if (!uploadForm.files.length) {
      setMessage("Bạn chưa chọn tệp ảnh.");
      return;
    }

    setSaving("image-upload");
    setMessage(null);
    const queue = [...uploadForm.files];
    const failedIds = new Set<string>();
    let uploadedCount = 0;

    // Deliberately sequential: Cloudinary uploads stay bounded and each successful
    // DB insert independently emits its PRODUCT_IMAGE_CREATED outbox event.
    for (let index = 0; index < queue.length; index += 1) {
      const item = queue[index];
      setUploadForm((current) => ({
        ...current,
        files: current.files.map((candidate) =>
          candidate.id === item.id ? { ...candidate, status: "uploading", error: null } : candidate
        )
      }));
      try {
        const formData = new FormData();
        formData.append("file", item.file);
        if (uploadForm.altText.trim()) formData.append("altText", uploadForm.altText.trim());
        if (uploadForm.color.trim()) formData.append("color", uploadForm.color.trim());
        formData.append("isPrimary", String(uploadForm.isPrimary && item.orderOffset === 0));
        formData.append("sortOrder", String(Number(uploadForm.sortOrder || 0) + item.orderOffset));
        await uploadProductImage(selectedProductId, formData);
        uploadedCount += 1;
        setUploadForm((current) => ({
          ...current,
          isPrimary: current.isPrimary && item.orderOffset === 0 ? false : current.isPrimary,
          files: current.files.filter((candidate) => candidate.id !== item.id)
        }));
      } catch (error) {
        failedIds.add(item.id);
        const errorMessage = extractError(error, "Không upload được ảnh");
        setUploadForm((current) => ({
          ...current,
          files: current.files.map((candidate) =>
            candidate.id === item.id ? { ...candidate, status: "failed", error: errorMessage } : candidate
          )
        }));
      }
    }

    try {
      if (uploadedCount > 0) await refreshDetail(selectedProductId);
      setMessage(
        failedIds.size
          ? `Đã upload ${uploadedCount}/${queue.length} ảnh. ${failedIds.size} ảnh lỗi được giữ lại để thử lại.`
          : `Đã upload tuần tự ${uploadedCount} ảnh. Embedding đang được xử lý trong hàng đợi.`
      );
    } catch (error) {
      setMessage(`Đã upload ${uploadedCount} ảnh nhưng chưa tải lại được thư viện ảnh. ${extractError(error, "Hãy tải lại trang.")}`);
    } finally {
      setSaving(null);
    }
  }

  async function handleUploadFilesSelected(fileList: FileList | null) {
    if (!fileList?.length) {
      setUploadForm((current) => ({ ...current, files: [] }));
      return;
    }
    const selected = Array.from(fileList).slice(0, PRODUCT_IMAGE_MAX_BATCH);
    const rejected: string[] = [];
    const inspected = await Promise.all(selected.map(async (file, index) => {
      if (!PRODUCT_IMAGE_MIME_TYPES.has(file.type)) {
        rejected.push(`${file.name}: sai định dạng`);
        return null;
      }
      if (file.size > PRODUCT_IMAGE_MAX_BYTES) {
        rejected.push(`${file.name}: vượt quá 5 MB`);
        return null;
      }
      try {
        const bitmap = await createImageBitmap(file);
        const { width, height } = bitmap;
        bitmap.close();
        const ratio = width / height;
        const idealRatio = PRODUCT_IMAGE_IDEAL_WIDTH / PRODUCT_IMAGE_IDEAL_HEIGHT;
        let warning: string | null = null;
        if (width < PRODUCT_IMAGE_MIN_WIDTH || height < PRODUCT_IMAGE_MIN_HEIGHT) {
          warning = `Ảnh hơi nhỏ; nên dùng ít nhất ${PRODUCT_IMAGE_MIN_WIDTH}×${PRODUCT_IMAGE_MIN_HEIGHT} px.`;
        } else if (Math.abs(ratio - idealRatio) > 0.05) {
          warning = "Ảnh không theo tỷ lệ 2:3 nên phần rìa có thể bị cắt.";
        }
        return { id: `${file.name}-${file.size}-${file.lastModified}-${index}`, file, width, height, orderOffset: index, warning, status: "pending" as const, error: null };
      } catch {
        rejected.push(`${file.name}: không đọc được ảnh`);
        return null;
      }
    }));
    const accepted = inspected
      .filter((item): item is NonNullable<typeof item> => item !== null)
      .map((item, orderOffset) => ({ ...item, orderOffset }));
    setUploadForm((current) => ({ ...current, files: accepted }));
    const excess = fileList.length > PRODUCT_IMAGE_MAX_BATCH ? ` Chỉ nhận ${PRODUCT_IMAGE_MAX_BATCH} ảnh đầu tiên.` : "";
    setMessage(rejected.length ? `${rejected.join(" | ")}.${excess}` : excess.trim() || null);
  }

  async function handleDeleteImage(image: ProductImageResponse) {
    if (!selectedProductId) {
      return;
    }

    try {
      setSaving(`image-delete-${image.id}`);
      setMessage(null);
      await deleteProductImage(selectedProductId, image.id);
      await refreshDetail(selectedProductId);
      setMessage("Đã xóa ảnh khỏi sản phẩm.");
    } catch (error) {
      setMessage(extractError(error, "Không xóa được ảnh"));
    } finally {
      setSaving(null);
    }
  }

  async function handleSetPrimaryImage(image: ProductImageResponse) {
    if (!selectedProductId || image.isPrimary) {
      return;
    }

    try {
      setSaving(`image-primary-${image.id}`);
      setMessage(null);
      await setPrimaryProductImage(selectedProductId, image.id);
      await refreshDetail(selectedProductId);
      setMessage("Đã đặt ảnh nổi bật cho sản phẩm.");
    } catch (error) {
      setMessage(extractError(error, "Không đặt được ảnh nổi bật"));
    } finally {
      setSaving(null);
    }
  }

  function startCreateProduct() {
    setSelectedProductId("");
    setDetail(null);
    setSlugDirty(false);
    setProductForm(createEmptyProductForm(categories[0]?.id ?? "", brands[0]?.id ?? ""));
    setVariantForm(createEmptyVariantForm());
    setVariantSkuDirty(false);
    setVariantDrafts({});
    setImageForm(createEmptyImageForm());
    setUploadForm(createEmptyUploadForm());
    setActiveTab("info");
    setMessage("Bạn đang ở chế độ tạo sản phẩm mới.");
  }
  return (
    <div className="admin-grid admin-grid-2">
      <section className="card panel">
        <div className="panel-header">
          <div>
            <h2>Catalog sản phẩm</h2>
            <p className="panel-copy">Danh sách sản phẩm đang được đồng bộ từ API quản trị.</p>
          </div>
          <button className="admin-btn" type="button" onClick={startCreateProduct}>
            Tạo sản phẩm mới
          </button>
        </div>
        <div className="admin-form-grid" style={{ marginBottom: 16 }}>
          <input
            className="admin-input"
            placeholder="Tìm theo tên, SKU, danh mục hoặc thương hiệu"
            value={searchTerm}
            onChange={(event) => setSearchTerm(event.target.value)}
          />
          <select className="select" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as "all" | AdminProduct["status"] | "featured")}>
            <option value="all">Tất cả trạng thái</option>
            <option value="active">Đang bán ổn định</option>
            <option value="low">Sắp hết hàng</option>
            <option value="out">Hết hàng</option>
            <option value="draft">Bản nháp / ẩn</option>
            <option value="featured">Sản phẩm nổi bật</option>
          </select>
        </div>

        {products.length === 0 ? (
          <div className="empty-state">Hiện chưa có sản phẩm nào trong database.</div>
        ) : filteredProducts.length === 0 ? (
          <div className="empty-state">Không có sản phẩm nào khớp bộ lọc hiện tại.</div>
        ) : (
          <>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Sản phẩm</th>
                  <th>SKU</th>
                  <th>Tồn</th>
                  <th>Trạng thái</th>
                </tr>
              </thead>
              <tbody>
                {pagedProducts.map((product) => (
                  <tr
                    key={product.id ?? product.sku}
                    className={selectedProductId && product.id === selectedProductId ? "row-selected" : ""}
                    onClick={() => product.id && setSelectedProductId(product.id)}
                  >
                    <td>
                      <div className="product-cell">
                        <Image
                          src={product.image}
                          alt={product.name}
                          width={52}
                          height={52}
                          unoptimized
                          onError={(event) => {
                            const img = event.currentTarget as HTMLImageElement;
                            if (img.src !== PRODUCT_THUMB_FALLBACK) {
                              img.src = PRODUCT_THUMB_FALLBACK;
                            }
                          }}
                        />
                        <div>
                          <strong>{product.name}</strong>
                          <div className="table-subtle">
                            {product.category} · {product.brand}
                            {product.isFeatured ? " · Nổi bật" : ""}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td>{product.sku}</td>
                    <td>{product.stock}</td>
                    <td>
                      <span className={`status ${product.status}`}>{product.status}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className="admin-pager">
              <span className="admin-pager-info">
                {(currentPage - 1) * PRODUCTS_PER_PAGE + 1}
                –{Math.min(currentPage * PRODUCTS_PER_PAGE, filteredProducts.length)} / {filteredProducts.length} sản phẩm
              </span>
              <div className="admin-pager-controls">
                <button
                  type="button"
                  className="admin-pager-btn"
                  onClick={() => setPage((current) => Math.max(1, current - 1))}
                  disabled={currentPage <= 1}
                >
                  Trước
                </button>
                <span className="admin-pager-page">
                  Trang {currentPage}/{totalPages}
                </span>
                <button
                  type="button"
                  className="admin-pager-btn"
                  onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
                  disabled={currentPage >= totalPages}
                >
                  Sau
                </button>
              </div>
            </div>
          </>
        )}
      </section>

      <section className="card product-editor">
        <div className="editor-head">
          {selectedProduct ? (
            <Image
              className="editor-thumb"
              src={selectedProductImage}
              alt={selectedProduct.name}
              width={56}
              height={56}
              unoptimized
              onError={(event) => {
                const img = event.currentTarget as HTMLImageElement;
                if (!img.src.includes("data:image/svg+xml")) {
                  img.src = PRODUCT_THUMB_FALLBACK;
                }
              }}
            />
          ) : (
            <span className="editor-thumb placeholder">＋</span>
          )}
          <div className="editor-identity">
            <h2>{selectedProduct ? selectedProduct.name : "Sản phẩm mới"}</h2>
            <div className="table-subtle">
              {selectedProduct
                ? [selectedProduct.category, selectedProduct.brand, selectedProduct.sku].filter(Boolean).join(" · ")
                : "Điền thông tin và lưu để mở khoá biến thể và hình ảnh."}
            </div>
          </div>
          {selectedProduct ? <span className={`status ${selectedProduct.status}`}>{selectedProduct.status}</span> : null}
        </div>

        <div className="editor-tabs" role="tablist">
          <button type="button" className={`editor-tab${activeTab === "info" ? " active" : ""}`} onClick={() => setActiveTab("info")}>
            Thông tin
          </button>
          <button type="button" className={`editor-tab${activeTab === "variants" ? " active" : ""}`} onClick={() => setActiveTab("variants")}>
            Biến thể
            {detail?.variants.length ? <span className="tab-count">{detail.variants.length}</span> : null}
          </button>
          <button type="button" className={`editor-tab${activeTab === "images" ? " active" : ""}`} onClick={() => setActiveTab("images")}>
            Hình ảnh
            {detail?.images.length ? <span className="tab-count">{detail.images.length}</span> : null}
          </button>
        </div>

        <div className="publish-checklist" aria-label="Điều kiện xuất bản sản phẩm">
          <div className="publish-checklist-head">
            <strong>{canPublishActive ? "Sẵn sàng bán" : "Chưa đủ điều kiện bán"}</strong>
            <span className={`status ${canPublishActive ? "active" : "draft"}`}>
              {publishChecklist.filter((item) => item.done).length}/{publishChecklist.length}
            </span>
          </div>
          <div className="readiness-grid">
            {publishChecklist.map((item) => (
              <button
                type="button"
                key={item.key}
                className={`readiness-item${item.done ? " done" : ""}`}
                onClick={() => {
                  if (item.key === "variants" || item.key === "prices") setActiveTab("variants");
                  else if (item.key === "images") setActiveTab("images");
                  else setActiveTab("info");
                }}
              >
                <span className="readiness-dot" aria-hidden />
                <span>
                  <strong>{item.label}</strong>
                  <small>{item.done ? "Đã đủ" : item.help}</small>
                </span>
              </button>
            ))}
          </div>
        </div>

        {message ? <p className="action-message">{message}</p> : null}

        {activeTab === "info" ? (
          <div className="editor-panel">
            <div className="editor-section-head">
              <h3>Thông tin sản phẩm</h3>
              <p className="table-subtle">Tên, phân loại và mô tả hiển thị trên storefront.</p>
            </div>
            {detailLoading ? (
              <div className="loading-state">
                <div className="skeleton" style={{ width: "42%", marginBottom: 10 }} />
                <div className="skeleton" style={{ width: "100%", marginBottom: 8 }} />
                <div className="skeleton" style={{ width: "86%" }} />
              </div>
            ) : null}
            {missingCategorySetup || missingBrandSetup ? (
              <div className="empty-state">
                {[
                  missingCategorySetup ? "Danh mục đang trống" : null,
                  missingBrandSetup ? "Thương hiệu đang trống" : null
                ]
                  .filter(Boolean)
                  .join(" · ")}
                . Hãy tạo dữ liệu nền ở trang quản trị tương ứng rồi quay lại đây.
              </div>
            ) : null}
            <div className="admin-form-grid">
              <input className="admin-input" placeholder="Tên sản phẩm" value={productForm.name} onChange={(event) => {
                const name = event.target.value;
                setProductForm((current) => ({ ...current, name, ...(!slugDirty && { slug: toSlug(name) }) }));
              }} />
              <input className="admin-input" placeholder="Slug" value={productForm.slug} onChange={(event) => {
                setSlugDirty(true);
                setProductForm((current) => ({ ...current, slug: event.target.value }));
              }} />
              <select className="select" value={productForm.categoryId} onChange={(event) => setProductForm((current) => ({ ...current, categoryId: event.target.value }))} disabled={missingCategorySetup}>
                {missingCategorySetup ? <option value="">Chưa có danh mục</option> : null}
                {categories.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
              </select>
              <select className="select" value={productForm.brandId} onChange={(event) => setProductForm((current) => ({ ...current, brandId: event.target.value }))} disabled={missingBrandSetup}>
                {missingBrandSetup ? <option value="">Chưa có thương hiệu</option> : null}
                {brands.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
              </select>
              <select className="select" value={productForm.gender} onChange={(event) => setProductForm((current) => ({ ...current, gender: event.target.value }))}>
                <option value="">Không chọn giới tính</option>
                {genderOptions.map((item) => <option value={item} key={item}>{item}</option>)}
              </select>
              <select className="select" value={productForm.status} onChange={(event) => setProductForm((current) => ({ ...current, status: event.target.value }))}>
                {productStatusOptions.map((item) => (
                  <option value={item} key={item} disabled={item === "ACTIVE" && !canPublishActive}>
                    {item === "DRAFT" ? "DRAFT - Bản nháp" : item === "ACTIVE" ? "ACTIVE - Đang bán" : "INACTIVE - Ẩn/ngừng bán"}
                  </option>
                ))}
              </select>
              <select className="select" value={productForm.productType} onChange={(event) => setProductForm((current) => ({ ...current, productType: event.target.value }))}>
                {productTypeOptions.map((item) => <option value={item} key={item}>{item}</option>)}
              </select>
              <select className="select" value={productForm.sportType} onChange={(event) => setProductForm((current) => ({ ...current, sportType: event.target.value }))}>
                <option value="">Không chọn môn thể thao</option>
                {sportOptions.map((item) => <option value={item} key={item}>{item}</option>)}
              </select>
              {!selectedProductId ? (
                <select className="select" value={productForm.dataSource} onChange={(event) => setProductForm((current) => ({ ...current, dataSource: event.target.value }))}>
                  {dataSourceOptions.map((item) => <option value={item} key={item}>{item} - {item === "REAL" ? "Dữ liệu thật" : item === "DEMO" ? "Dữ liệu demo" : "Dữ liệu tổng hợp"}</option>)}
                </select>
              ) : null}
              <label className="admin-check">
                <input type="checkbox" checked={productForm.isFeatured} onChange={(event) => setProductForm((current) => ({ ...current, isFeatured: event.target.checked }))} />
                Sản phẩm nổi bật
              </label>
              <div className="admin-form-full">
                <input className="admin-input" placeholder="Mô tả ngắn" value={productForm.shortDescription} onChange={(event) => setProductForm((current) => ({ ...current, shortDescription: event.target.value }))} />
              </div>
              <div className="admin-form-full">
                <textarea className="admin-textarea" placeholder="Mô tả chi tiết" value={productForm.description} onChange={(event) => setProductForm((current) => ({ ...current, description: event.target.value }))} />
              </div>
            </div>
            <div className="page-actions">
              {selectedProductId && (
                <button className="admin-btn secondary" type="button" onClick={() => void handleDeleteProduct()} disabled={saving !== null}>
                  {saving === "product-delete" ? "Đang xóa..." : "Xóa sản phẩm"}
                </button>
              )}
              <button className="admin-btn" type="button" onClick={() => void handleCreateOrUpdateProduct()} disabled={!canSubmitProduct}>
                {saving === "product" ? "Đang lưu..." : selectedProductId ? "Cập nhật sản phẩm" : "Tạo sản phẩm"}
              </button>
            </div>
          </div>
        ) : null}

        {activeTab === "variants" ? (
          <div className="editor-panel">
            <div className="editor-section-head">
              <h3>Biến thể</h3>
              <p className="table-subtle">Tạo hàng loạt theo tổ hợp màu × size, hoặc thêm từng biến thể. Chỉnh tồn kho sau tạo dùng module tồn kho.</p>
            </div>
            {!selectedProductId ? (
              <div className="empty-state">Hãy lưu sản phẩm ở tab Thông tin trước khi thêm biến thể.</div>
            ) : (
              <>
                <div className="admin-subcard">
                  <div className="editor-subcard-title">Tạo nhanh nhiều biến thể</div>
                  <p className="table-subtle" style={{ marginBottom: 10 }}>
                    Nhập nhiều màu và size (cách nhau bằng dấu phẩy) → hệ thống tạo mọi tổ hợp. Bỏ trống màu nếu sản phẩm chỉ khác size.
                  </p>
                  <div className="admin-form-grid">
                    <input className="admin-input" placeholder="Màu (vd: All Black, Red, White)" value={genColors} onChange={(event) => setGenColors(event.target.value)} />
                    <input className="admin-input" placeholder="Size (vd: S, M, L, XL)" value={genSizes} onChange={(event) => setGenSizes(event.target.value)} />
                    <input className="admin-input" placeholder="Mã SKU cơ sở (tự sinh nếu trống)" value={genBaseSku} onChange={(event) => setGenBaseSku(event.target.value)} />
                  </div>
                  <div className="variant-chip-toolbar">
                    <span className="table-subtle">Size nhanh:</span>
                    {quickSizeOptions.map((size) => (
                      <button
                        type="button"
                        className={`variant-chip${parsedGenSizes.includes(size) ? " active" : ""}`}
                        key={size}
                        onClick={() => {
                          const next = new Set(parsedGenSizes);
                          if (next.has(size)) next.delete(size);
                          else next.add(size);
                          setGenSizes(Array.from(next).join(", "));
                        }}
                      >
                        {size}
                      </button>
                    ))}
                  </div>
                  {(parsedGenColors.length > 0 || parsedGenSizes.length > 0) ? (
                    <div className="variant-chip-preview">
                      {parsedGenColors.length > 0 ? (
                        <div>
                          <span className="table-subtle">Màu đã nhận:</span>
                          <div className="variant-chip-row">
                            {parsedGenColors.map((color) => <span className="variant-chip readonly" key={color}>{color}</span>)}
                          </div>
                        </div>
                      ) : null}
                      {parsedGenSizes.length > 0 ? (
                        <div>
                          <span className="table-subtle">Size đã nhận:</span>
                          <div className="variant-chip-row">
                            {parsedGenSizes.map((size) => <span className="variant-chip readonly" key={size}>{size}</span>)}
                          </div>
                        </div>
                      ) : null}
                    </div>
                  ) : null}
                  <div className="page-actions">
                    <button className="admin-btn secondary" type="button" onClick={handleGenerateMatrix}>
                      Tạo bảng
                    </button>
                  </div>

                  {genRows.length > 0 ? (
                    <>
                      <div className="admin-form-grid" style={{ marginTop: 12 }}>
                        <input className="admin-input" type="number" min={0} placeholder="Giá bán (áp dụng tất cả)" value={bulkPrice} onChange={(event) => setBulkPrice(event.target.value)} />
                        <input className="admin-input" type="number" min={0} placeholder="Giá so sánh" value={bulkCompare} onChange={(event) => setBulkCompare(event.target.value)} />
                        <input className="admin-input" type="number" min={0} placeholder="Tồn ban đầu" value={bulkStock} onChange={(event) => setBulkStock(event.target.value)} />
                        <button className="admin-btn secondary" type="button" onClick={applyBulkToRows}>
                          Áp dụng cho tất cả
                        </button>
                      </div>
                      <div style={{ overflowX: "auto", marginTop: 10 }}>
                        <table className="data-table">
                          <thead>
                            <tr>
                              <th>Màu</th>
                              <th>Size</th>
                              <th>SKU</th>
                              <th>Giá bán</th>
                              <th>Giá so sánh</th>
                              <th>Tồn</th>
                              <th />
                            </tr>
                          </thead>
                          <tbody>
                            {genRows.map((row) => (
                              <tr key={row.key}>
                                <td>{row.color || "—"}</td>
                                <td>{row.size || "—"}</td>
                                <td>
                                  <input className="admin-input" value={row.sku} onChange={(event) => updateGenRow(row.key, { sku: event.target.value })} />
                                </td>
                                <td>
                                  <input className="admin-input" type="number" min={0} value={row.price} onChange={(event) => updateGenRow(row.key, { price: event.target.value })} />
                                </td>
                                <td>
                                  <input className="admin-input" type="number" min={0} value={row.compareAtPrice} onChange={(event) => updateGenRow(row.key, { compareAtPrice: event.target.value })} />
                                </td>
                                <td>
                                  <input className="admin-input" type="number" min={0} value={row.stockQuantity} onChange={(event) => updateGenRow(row.key, { stockQuantity: event.target.value })} />
                                </td>
                                <td>
                                  <button className="admin-btn secondary" type="button" onClick={() => removeGenRow(row.key)}>
                                    Xoá
                                  </button>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                      <div className="page-actions">
                        <button className="admin-btn" type="button" onClick={() => void handleSaveAllVariants()} disabled={saving === "variant-bulk"}>
                          {saving === "variant-bulk" ? "Đang tạo..." : `Lưu tất cả (${genRows.length})`}
                        </button>
                      </div>
                    </>
                  ) : null}
                </div>

                <hr className="editor-divider" />
                <div className="editor-subcard-title">Thêm một biến thể</div>
                <p className="table-subtle">SKU được tự sinh theo mã sản phẩm–màu–size. Bạn vẫn có thể sửa thủ công.</p>
                <div className="admin-form-grid">
                  <input className="admin-input" placeholder="SKU (tự sinh)" value={variantForm.sku} onChange={(event) => {
                    setVariantSkuDirty(event.target.value.trim().length > 0);
                    setVariantForm((current) => ({ ...current, sku: event.target.value }));
                  }} />
                  <input className="admin-input" placeholder="Size" value={variantForm.size} onChange={(event) => {
                    const size = event.target.value;
                    setVariantForm((current) => ({
                      ...current,
                      size,
                      sku: variantSkuDirty ? current.sku : buildUniqueVariantSku(detail?.slug ?? productForm.slug ?? productForm.name, current.color, size, detail?.variants.map((variant) => variant.sku) ?? [])
                    }));
                  }} />
                  <input className="admin-input" placeholder="Màu sắc" value={variantForm.color} onChange={(event) => {
                    const color = event.target.value;
                    setVariantForm((current) => ({
                      ...current,
                      color,
                      sku: variantSkuDirty ? current.sku : buildUniqueVariantSku(detail?.slug ?? productForm.slug ?? productForm.name, color, current.size, detail?.variants.map((variant) => variant.sku) ?? [])
                    }));
                  }} />
                  <input className="admin-input" type="number" min={0} placeholder="Giá bán" value={variantForm.price} onChange={(event) => setVariantForm((current) => ({ ...current, price: event.target.value }))} />
                  <input className="admin-input" type="number" min={0} placeholder="Giá so sánh" value={variantForm.compareAtPrice} onChange={(event) => setVariantForm((current) => ({ ...current, compareAtPrice: event.target.value }))} />
                  <input className="admin-input" type="number" min={0} placeholder="Tồn kho" value={variantForm.stockQuantity} onChange={(event) => setVariantForm((current) => ({ ...current, stockQuantity: event.target.value }))} />
                  <select className="select" value={variantForm.status} onChange={(event) => setVariantForm((current) => ({ ...current, status: event.target.value }))}>
                    {variantStatusOptions.map((item) => <option value={item} key={item}>{item}</option>)}
                  </select>
                </div>
                <div className="page-actions">
                  <button className="admin-btn" type="button" onClick={() => void handleCreateVariant()} disabled={saving === "variant-create"}>
                    {saving === "variant-create" ? "Đang thêm..." : "Thêm biến thể"}
                  </button>
                </div>
                <hr className="editor-divider" />
                {detail?.variants.length ? (
                  <div className="admin-stack">
                    {groupVariantsByColor(detail.variants).map(([color, variantsInColor]) => (
                      <div className="variant-color-group" key={color || "__no_color__"}>
                        <div className="variant-color-head">
                          <span className="editor-subcard-title">Màu: {color || "— (chưa đặt màu)"}</span>
                          <span className="tab-count">{variantsInColor.length} size</span>
                        </div>
                        <div className="variant-size-table-wrap">
                          <table className="variant-size-table">
                            <thead>
                              <tr>
                                <th>Size</th>
                                <th>Giá bán</th>
                                <th>Giá so sánh</th>
                                <th>Trạng thái</th>
                                <th>Tồn</th>
                                <th aria-hidden />
                              </tr>
                            </thead>
                            <tbody>
                              {variantsInColor.map((variant) => {
                                const draft = variantDrafts[variant.id];
                                if (!draft) return null;
                                return (
                                  <VariantEditorCard
                                    key={variant.id}
                                    variant={variant}
                                    draft={draft}
                                    saving={saving === `variant-${variant.id}`}
                                    onDraftChange={(patch) => setVariantDrafts((current) => ({ ...current, [variant.id]: { ...current[variant.id], ...patch } }))}
                                    onSave={() => void handleUpdateVariant(variant.id)}
                                  />
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : <div className="empty-state">Sản phẩm này chưa có biến thể.</div>}
              </>
            )}
          </div>
        ) : null}

        {activeTab === "images" ? (
          <div className="editor-panel">
            <div className="editor-section-head">
              <h3>Hình ảnh</h3>
              <p className="table-subtle">Hỗ trợ cả thêm URL có sẵn và upload ảnh thật.</p>
            </div>
            {!selectedProductId ? (
              <div className="empty-state">Hãy lưu sản phẩm ở tab Thông tin trước khi thêm hình ảnh.</div>
            ) : (
              <>
                <div className="admin-subcard">
                  <div className="editor-subcard-title">Thêm bằng URL</div>
                  <div className="admin-form-grid">
                    <div className="admin-form-full">
                      <input className="admin-input" placeholder="Image URL" value={imageForm.imageUrl} onChange={(event) => setImageForm((current) => ({ ...current, imageUrl: event.target.value }))} />
                    </div>
                    <input className="admin-input" placeholder="Mô tả ảnh (alt text)" value={imageForm.altText} onChange={(event) => setImageForm((current) => ({ ...current, altText: event.target.value }))} />
                    <select className="admin-input" aria-label="Màu áp dụng cho ảnh" value={imageForm.color} onChange={(event) => setImageForm((current) => ({ ...current, color: event.target.value }))}>
                      <option value="">Dùng chung cho mọi màu</option>
                      {Array.from(new Set((detail?.variants ?? []).map((v) => v.color).filter((c): c is string => Boolean(c)))).map((c) => (
                        <option value={c} key={c}>{c}</option>
                      ))}
                    </select>
                    <input className="admin-input" type="number" min={0} aria-label="Thứ tự hiển thị" placeholder="Thứ tự hiển thị" value={imageForm.sortOrder} onChange={(event) => setImageForm((current) => ({ ...current, sortOrder: event.target.value }))} />
                    <label className="admin-check">
                      <input type="checkbox" checked={imageForm.isPrimary} onChange={(event) => setImageForm((current) => ({ ...current, isPrimary: event.target.checked }))} />
                      Đặt làm ảnh chính
                    </label>
                  </div>
                  <div className="page-actions">
                    <button className="admin-btn secondary" type="button" onClick={() => void handleAddImageByUrl()} disabled={saving === "image-url"}>
                      {saving === "image-url" ? "Đang thêm..." : "Thêm ảnh bằng URL"}
                    </button>
                  </div>
                </div>
                <div className="admin-subcard">
                  <div className="editor-subcard-title">Upload ảnh thật</div>
                  <p className="table-subtle">
                    Chọn tối đa {PRODUCT_IMAGE_MAX_BATCH} ảnh. Khuyến nghị {PRODUCT_IMAGE_IDEAL_WIDTH}×{PRODUCT_IMAGE_IDEAL_HEIGHT} px (tỷ lệ 2:3), tối thiểu {PRODUCT_IMAGE_MIN_WIDTH}×{PRODUCT_IMAGE_MIN_HEIGHT} px, JPEG/PNG/WebP và không quá 5 MB mỗi ảnh.
                  </p>
                  <div className="admin-form-grid">
                    <input className="admin-input admin-form-full" type="file" multiple accept="image/jpeg,image/png,image/webp" disabled={saving === "image-upload"} onChange={(event) => {
                      void handleUploadFilesSelected(event.currentTarget.files);
                      event.currentTarget.value = "";
                    }} />
                    {uploadForm.files.length ? (
                      <div className="admin-form-full table-subtle">
                        <strong>Đã chọn {uploadForm.files.length} ảnh (upload tuần tự)</strong>
                        {uploadForm.files.map((item, index) => (
                          <div key={item.id} style={{ marginTop: 6 }}>
                            {index + 1}. {item.file.name} · {item.width}×{item.height} px · {(item.file.size / 1024 / 1024).toFixed(2)} MB
                            {item.status === "uploading" ? " · Đang upload..." : item.status === "failed" ? " · Upload lỗi" : ""}
                            {item.warning ? <div style={{ color: "var(--admin-warning, #b45309)" }}>{item.warning}</div> : null}
                            {item.error ? <div style={{ color: "var(--admin-danger, #b91c1c)" }}>{item.error}</div> : null}
                          </div>
                        ))}
                      </div>
                    ) : null}
                    <input className="admin-input" placeholder="Mô tả chung cho các ảnh (alt text)" value={uploadForm.altText} onChange={(event) => setUploadForm((current) => ({ ...current, altText: event.target.value }))} />
                    <select className="admin-input" aria-label="Màu áp dụng cho ảnh upload" value={uploadForm.color} onChange={(event) => setUploadForm((current) => ({ ...current, color: event.target.value }))}>
                      <option value="">Dùng chung cho mọi màu</option>
                      {Array.from(new Set((detail?.variants ?? []).map((v) => v.color).filter((c): c is string => Boolean(c)))).map((c) => (
                        <option value={c} key={c}>{c}</option>
                      ))}
                    </select>
                    <input className="admin-input" type="number" min={0} aria-label="Thứ tự hiển thị bắt đầu" placeholder="Thứ tự hiển thị bắt đầu" value={uploadForm.sortOrder} onChange={(event) => setUploadForm((current) => ({ ...current, sortOrder: event.target.value }))} />
                    <label className="admin-check">
                      <input type="checkbox" checked={uploadForm.isPrimary} onChange={(event) => setUploadForm((current) => ({ ...current, isPrimary: event.target.checked }))} />
                      Đặt ảnh đầu tiên làm ảnh chính
                    </label>
                  </div>
                  <div className="page-actions">
                    <button className="admin-btn" type="button" onClick={() => void handleUploadImage()} disabled={saving === "image-upload" || uploadForm.files.length === 0}>
                      {saving === "image-upload" ? "Đang upload tuần tự..." : `Upload ${uploadForm.files.length || "các"} ảnh`}
                    </button>
                  </div>
                </div>
                <hr className="editor-divider" />
                {detail?.images.length ? (
                  <div className="admin-gallery">
                    {detail.images.map((image) => (
                      <ProductImageCard
                        key={image.id}
                        image={image}
                        productName={detail.name}
                        saving={saving === `image-delete-${image.id}`}
                        settingPrimary={saving === `image-primary-${image.id}`}
                        onDelete={() => void handleDeleteImage(image)}
                        onSetPrimary={() => void handleSetPrimaryImage(image)}
                      />
                    ))}
                  </div>
                ) : <div className="empty-state">Sản phẩm này chưa có ảnh.</div>}
              </>
            )}
          </div>
        ) : null}

      </section>
    </div>
  );
}
