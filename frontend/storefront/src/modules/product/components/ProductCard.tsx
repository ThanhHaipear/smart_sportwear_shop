"use client";

import { useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { cn } from "@/lib/utils";
import { shouldBypassImageOptimization } from "@/lib/image";
import { HeartIcon, ShoppingBagIcon } from "@/components/shared/icons";
import type { Product } from "@/modules/product/types";

function formatPrice(price: number | null | undefined): string {
  if (price == null) return "Liên hệ";
  return `${price.toLocaleString("vi-VN")}đ`;
}

export function ProductCard({ product }: { product: Product }) {
  const [selectedColorId, setSelectedColorId] = useState<string | undefined>(
    product.colors.find((c) => c.active)?.id ?? product.colors[0]?.id,
  );
  const [sizePopupOpen, setSizePopupOpen] = useState(false);

  return (
    <div
      className="item-new-prod w-full"
      onMouseEnter={() => setSizePopupOpen(true)}
      onMouseLeave={() => setSizePopupOpen(false)}
    >
      <div className="product relative">
        {product.ribbon && !product.isOutOfStock ? (
          <span
            className={cn(
              "absolute left-0 top-2 z-10 px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.04em] text-white",
              product.ribbon === "sale"
                ? "bg-[#E60012]"
                : product.ribbon === "bestseller"
                  ? "bg-[#AC2F33]"
                  : "bg-[#E7973E]",
            )}
          >
            {product.ribbon === "sale" ? "Flash Sale" : product.ribbon === "bestseller" ? "Best Seller" : "NEW"}
          </span>
        ) : null}
        {product.discountPercent ? (
          <span className="badget absolute right-2 top-2 z-10 rounded-bl-[14px] rounded-tr-[14px] bg-[#E7A746] px-2 py-1 text-[11px] font-semibold text-white">
            -{product.discountPercent}%
          </span>
        ) : null}
        <div className="thumb-product group relative mb-4 aspect-square overflow-hidden bg-[#f7f7f7]">
          <Link href={product.href} className="relative block h-full">
            <Image
              src={product.image}
              alt={product.name}
              fill
              sizes="(max-width: 767px) 46vw, (max-width: 1279px) 30vw, 332px"
              className="h-full w-full object-contain object-center p-3"
              unoptimized={shouldBypassImageOptimization(product.image)}
            />
            <Image
              src={product.hoverImage}
              alt={product.name}
              fill
              sizes="(max-width: 767px) 46vw, (max-width: 1279px) 30vw, 332px"
              className="hover-img invisible absolute inset-0 h-full w-full object-contain object-center p-3 opacity-0 transition-all duration-300 ease-in-out group-hover:visible group-hover:opacity-100"
              unoptimized={shouldBypassImageOptimization(product.hoverImage)}
            />
          </Link>

          {product.isOutOfStock ? (
            <span className="pointer-events-none absolute left-3 top-3 z-20 rounded-tl-[10px] rounded-br-[10px] bg-[#221F20]/95 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-[0.08em] text-white shadow-sm">
              Hết hàng
            </span>
          ) : null}

          <div className="add-to-cart absolute bottom-0 right-0">
            <button
              type="button"
              disabled={product.isOutOfStock}
              className="flex h-8 w-8 items-center justify-center rounded-tl-[16px] rounded-br-[16px] border border-transparent bg-[#221F20] text-white transition-colors hover:border-[#221F20] hover:bg-white hover:text-[#221F20] disabled:cursor-not-allowed disabled:bg-[#A8A9AD] disabled:text-white"
              aria-label={product.isOutOfStock ? "Sản phẩm đã hết hàng" : "Thêm vào giỏ hàng"}
            >
              <ShoppingBagIcon className="h-4 w-4" />
            </button>
          </div>

          {product.sizes.length > 0 ? (
            <ul
              className={cn(
                "list-size absolute right-0 border border-[#E7E8E9] bg-white pt-4 transition-all duration-300 ease-in-out",
                sizePopupOpen ? "visible bottom-[35px] opacity-100" : "invisible bottom-0 opacity-0",
              )}
            >
              {product.sizes.map((size) => (
                <li key={size.id} className={cn(size.disabled && "unactive")}>
                  <a
                    href={size.disabled ? undefined : product.href}
                    className={cn(
                      "mb-4 block px-4 text-base font-semibold leading-6 text-[#57585A] hover:text-[#221F20]",
                      size.disabled && "pointer-events-none text-[#D1D2D4]",
                    )}
                  >
                    {size.label.toUpperCase()}
                  </a>
                </li>
              ))}
            </ul>
          ) : null}
        </div>

        <div className="info-product">
          <div className="list-color mb-3 flex justify-between">
            <ul className="flex">
              {product.colors.map((color) => (
                <li
                  key={color.id}
                  className={cn(
                    "relative mr-2.5 h-[18px] w-[18px] overflow-hidden rounded-full border border-[#d8d8db]",
                    selectedColorId === color.id && "border-[#221F20]",
                  )}
                >
                  <button
                    type="button"
                    onClick={() => setSelectedColorId(color.id)}
                    aria-label={color.label}
                    className="block h-full w-full cursor-pointer"
                  >
                    <Image
                      src={color.image}
                      alt={color.label}
                      width={18}
                      height={18}
                      className="h-full w-full object-cover"
                      unoptimized={shouldBypassImageOptimization(color.image)}
                    />
                  </button>
                </li>
              ))}
            </ul>
            <button type="button" aria-label="Yêu thích" className="cursor-pointer">
              <HeartIcon className="h-[18px] w-[18px] text-[#8c8d91]" />
            </button>
          </div>

          <h3 className="title-product">
            <Link
              href={product.href}
              className="mb-2 block truncate text-[14px] leading-6 font-normal text-[#57585A]"
            >
              {product.name}
            </Link>
          </h3>

          <div className="price-product">
            <ins className="inline-block align-middle text-[16px] leading-6 font-semibold text-[#3E3E3F] no-underline md:text-[18px]">
              {formatPrice(product.price)}
            </ins>
            {product.oldPrice ? (
              <del className="ml-2 inline-block align-middle text-xs leading-4 font-normal text-[#A8A9AD]">
                {formatPrice(product.oldPrice)}
              </del>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}

