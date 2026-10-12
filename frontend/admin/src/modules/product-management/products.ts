import { NO_IMAGE } from "@/modules/ui/placeholder";

export type AdminProduct = {
  id?: string;
  sku: string;
  name: string;
  category: string;
  brand: string;
  price: string;
  stock: number;
  sold: number;
  status: "active" | "draft" | "low" | "out";
  isFeatured?: boolean;
  image: string;
};

export const adminProducts: AdminProduct[] = [
  {
    sku: "NIKE-V16-PRO-TF-42",
    name: "Nike Zoom Mercurial Vapor 16 Pro TF",
    category: "Giày cỏ nhân tạo",
    brand: "Nike",
    price: "3.350.000 ₫",
    stock: 84,
    sold: 318,
    status: "active",
    image: NO_IMAGE
  },
  {
    sku: "MIZ-ALPHA-AS-43",
    name: "Mizuno Alpha 3 Pro AS",
    category: "Giày cỏ nhân tạo",
    brand: "Mizuno",
    price: "2.700.000 ₫",
    stock: 6,
    sold: 185,
    status: "low",
    image: NO_IMAGE
  },
  {
    sku: "MIZ-MORELIA-SALA-41",
    name: "Mizuno Morelia Sala Elite TF",
    category: "Giày futsal",
    brand: "Mizuno",
    price: "2.970.000 ₫",
    stock: 14,
    sold: 242,
    status: "low",
    image: NO_IMAGE
  },
  {
    sku: "ADI-PRED-PRO-TF-40",
    name: "Adidas Predator Pro lưỡi gà lật TF",
    category: "Giày cỏ nhân tạo",
    brand: "Adidas",
    price: "3.050.000 ₫",
    stock: 42,
    sold: 211,
    status: "active",
    image: NO_IMAGE
  },
  {
    sku: "NIKE-GATO-IC-43",
    name: "Nike Gato IC",
    category: "Giày futsal",
    brand: "Nike",
    price: "1.590.000 ₫",
    stock: 0,
    sold: 97,
    status: "out",
    image: NO_IMAGE
  },
  {
    sku: "DHQ-BALO-TRAINING",
    name: "Balo DHQ Training",
    category: "Phụ kiện",
    brand: "DHQ",
    price: "390.000 ₫",
    stock: 120,
    sold: 146,
    status: "draft",
    image: NO_IMAGE
  }
];

export const productStats = [
  { label: "Tổng sản phẩm", value: "6", tone: "neutral" },
  { label: "Đang bán", value: "4", tone: "success" },
  { label: "Sắp hết hàng", value: "2", tone: "warning" },
  { label: "Hết hàng", value: "1", tone: "danger" }
];

