import { apiRequest } from "@/modules/api/client";
import { adminEndpoints } from "@/modules/api/endpoints";
import type {
  BrandResponse,
  CategoryResponse,
  ProductDetailResponse
} from "@/modules/catalog-admin/types";

export async function listAdminCategories() {
  return apiRequest<CategoryResponse[]>(adminEndpoints.categories, { next: { revalidate: 30 } });
}

export async function listAdminBrands() {
  return apiRequest<BrandResponse[]>(adminEndpoints.brands, { next: { revalidate: 30 } });
}

export async function getAdminProductDetail(id: string) {
  return apiRequest<ProductDetailResponse>(adminEndpoints.productDetail(id), { next: { revalidate: 30 } });
}
