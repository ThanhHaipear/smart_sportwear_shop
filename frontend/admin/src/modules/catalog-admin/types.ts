export type CatalogRef = {
  id: string;
  name: string;
};

export type ProductImageResponse = {
  id: string;
  imageUrl: string;
  publicId: string | null;
  altText: string | null;
  color: string | null;
  isPrimary: boolean;
  sortOrder: number;
};

export type ProductVariantResponse = {
  id: string;
  sku: string;
  size: string | null;
  color: string | null;
  price: number | null;
  compareAtPrice: number | null;
  availableQuantity: number;
  status: string;
};

export type ProductDetailResponse = {
  id: string;
  name: string;
  slug: string;
  shortDescription: string | null;
  description: string | null;
  gender: string | null;
  sportType: string | null;
  productType: "SPORTS_TOP" | "SPORTS_BOTTOM" | "SPORTS_SHOES" | "SPORTS_UNIFORM" | "SPORTS_ACCESSORY" | null;
  brand: CatalogRef | null;
  category: CatalogRef | null;
  images: ProductImageResponse[];
  variants: ProductVariantResponse[];
  status: string;
  isFeatured: boolean;
};

export type CategoryResponse = {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  status: string;
  parentId?: string | null;
  parentName?: string | null;
  parentSlug?: string | null;
  nodeType?: "GROUP" | "LEAF";
  sortOrder?: number;
};

export type BrandResponse = {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  status: string;
};

export type ProductImageUploadResponse = {
  id: string;
  imageUrl: string;
  publicId: string | null;
  altText: string | null;
  isPrimary: boolean;
  sortOrder: number;
  width: number | null;
  height: number | null;
};
