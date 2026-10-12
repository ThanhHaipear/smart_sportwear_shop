import { browserApiRequest } from "@/modules/api/browser-client";
import { adminEndpoints } from "@/modules/api/endpoints";
import type {
  BrandResponse,
  CategoryResponse,
  ProductDetailResponse,
  ProductImageResponse,
  ProductImageUploadResponse,
  ProductVariantResponse
} from "@/modules/catalog-admin/types";

export async function createAdminProduct(input: Record<string, unknown>) {
  return browserApiRequest<ProductDetailResponse>(adminEndpoints.products, {
    method: "POST",
    body: JSON.stringify(input)
  });
}

export async function fetchAdminProductDetail(id: string) {
  return browserApiRequest<ProductDetailResponse>(adminEndpoints.productDetail(id), {
    method: "GET"
  });
}

export async function updateAdminProduct(id: string, input: Record<string, unknown>) {
  return browserApiRequest<ProductDetailResponse>(adminEndpoints.productDetail(id), {
    method: "PATCH",
    body: JSON.stringify(input)
  });
}

export async function createVariant(productId: string, input: Record<string, unknown>) {
  return browserApiRequest<ProductVariantResponse>(adminEndpoints.productVariants(productId), {
    method: "POST",
    body: JSON.stringify(input)
  });
}

export async function updateVariant(id: string, input: Record<string, unknown>) {
  return browserApiRequest<ProductVariantResponse>(adminEndpoints.variant(id), {
    method: "PATCH",
    body: JSON.stringify(input)
  });
}

export async function addProductImage(productId: string, input: Record<string, unknown>) {
  return browserApiRequest<ProductImageResponse>(adminEndpoints.productImages(productId), {
    method: "POST",
    body: JSON.stringify(input)
  });
}

export async function uploadProductImage(productId: string, formData: FormData) {
  return browserApiRequest<ProductImageUploadResponse>(adminEndpoints.productImageUpload(productId), {
    method: "POST",
    body: formData
  });
}

export async function deleteProductImage(productId: string, imageId: string) {
  return browserApiRequest<ProductImageResponse[]>(adminEndpoints.productImageDelete(productId, imageId), {
    method: "DELETE"
  });
}

export async function setPrimaryProductImage(productId: string, imageId: string) {
  return browserApiRequest<ProductImageResponse[]>(adminEndpoints.productImagePrimary(productId, imageId), {
    method: "PATCH"
  });
}

export async function deleteProduct(id: string) {
  return browserApiRequest<void>(adminEndpoints.productDetail(id), { method: "DELETE" });
}

export async function createCategory(input: Record<string, unknown>) {
  return browserApiRequest<CategoryResponse>(adminEndpoints.adminCategories, {
    method: "POST",
    body: JSON.stringify(input)
  });
}

export async function updateCategory(id: string, input: Record<string, unknown>) {
  return browserApiRequest<CategoryResponse>(adminEndpoints.adminCategory(id), {
    method: "PATCH",
    body: JSON.stringify(input)
  });
}

export async function deleteCategory(id: string) {
  return browserApiRequest<void>(adminEndpoints.adminCategory(id), { method: "DELETE" });
}

export async function createBrand(input: Record<string, unknown>) {
  return browserApiRequest<BrandResponse>(adminEndpoints.adminBrands, {
    method: "POST",
    body: JSON.stringify(input)
  });
}

export async function updateBrand(id: string, input: Record<string, unknown>) {
  return browserApiRequest<BrandResponse>(adminEndpoints.adminBrand(id), {
    method: "PATCH",
    body: JSON.stringify(input)
  });
}

export async function deleteBrand(id: string) {
  return browserApiRequest<void>(adminEndpoints.adminBrand(id), { method: "DELETE" });
}

export type ProductPickItem = {
  id: string;
  name: string;
  slug: string;
  status: string;
  thumbnail?: string | null;
  brand?: { id: string; name: string } | null;
  category?: { id: string; name: string } | null;
  brandName?: string | null;
  categoryName?: string | null;
};

export async function listProductsForPicker() {
  return browserApiRequest<ProductPickItem[]>(`${adminEndpoints.products}?limit=200`, { method: "GET" });
}

export async function listAllProductsForPicker() {
  const all: ProductPickItem[] = [];
  for (let page = 1; page <= 100; page += 1) {
    const batch = await browserApiRequest<ProductPickItem[]>(adminEndpoints.products, {
      method: "GET",
      query: { page, limit: 100 }
    });
    all.push(...batch);
    if (batch.length < 100) break;
  }
  return all;
}

export async function listCategoriesForPicker() {
  return browserApiRequest<CategoryResponse[]>(adminEndpoints.categories, { method: "GET" });
}

export async function listBrandsForPicker() {
  return browserApiRequest<BrandResponse[]>(adminEndpoints.brands, { method: "GET" });
}
