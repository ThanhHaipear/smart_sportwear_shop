"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { shouldBypassImageOptimization } from "@/lib/image";
import { Minus, Plus, Ruler, Star } from "lucide-react";
import { HeartIcon } from "@/components/shared/icons";
import { getApiErrorMessage } from "@/lib/api-errors";
import { cn } from "@/lib/utils";
import { emitSessionChange, getAccessToken } from "@/lib/session";
import { toast } from "@/lib/toast";
import { addWishlistItem } from "@/modules/account/api";
import { addCartItem } from "@/modules/cart/api";
import { saveBuyNowSelection } from "@/modules/checkout/selection";
import type { ProductVariant } from "@/modules/product/types";

export interface ProductColor {
  id: string;
  image: string;
  label: string;
  active?: boolean;
}

export interface ProductSize {
  id: string;
  label: string;
  quantity: number;
}

interface ProductPurchasePanelProps {
  productId: string;
  name: string;
  sku: string;
  ratingPercentage: number;
  reviewCount: number;
  price: number;
  colors: ProductColor[];
  sizes: ProductSize[];
  variants: ProductVariant[];
  // Color is controlled by the parent so the gallery can react to it too.
  selectedColorLabel: string;
  onSelectColor: (label: string) => void;
}

const SIZE_ORDER = ["XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "2XL", "3XL", "4XL"];

// Natural size ordering: letter sizes by the scale above, numeric (shoe) sizes ascending after them.
function sizeRank(label: string) {
  const idx = SIZE_ORDER.indexOf(label.trim().toUpperCase());
  if (idx !== -1) return idx;
  const num = Number(label);
  return Number.isFinite(num) ? 100 + num : 999;
}

export function ProductPurchasePanel({
  productId,
  name,
  sku,
  ratingPercentage,
  reviewCount,
  price,
  colors,
  sizes,
  variants,
  selectedColorLabel,
  onSelectColor,
}: ProductPurchasePanelProps) {
  const router = useRouter();
  const [selectedSizeLabel, setSelectedSizeLabel] = useState(
    sizes.find((size) => size.quantity > 0)?.label ?? sizes[0]?.label ?? "",
  );
  const [quantity, setQuantity] = useState(1);
  const [pending, setPending] = useState<"cart" | "buy" | "wishlist" | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sizeGuideOpen, setSizeGuideOpen] = useState(false);

  const selectedColor = colors.find((color) => color.label === selectedColorLabel);
  const filledStars = Math.floor(ratingPercentage / 20);

  // Every size the product offers (across all colors), in natural order — so the
  // customer always sees the full size run, not just what's in stock right now.
  const allSizes = useMemo(() => {
    const labels = new Set<string>();
    variants.forEach((variant) => {
      if (variant.size) labels.add(variant.size);
    });
    if (labels.size === 0) return sizes.map((size) => size.label);
    return Array.from(labels).sort((a, b) => sizeRank(a) - sizeRank(b));
  }, [variants, sizes]);

  // Available quantity per size for the currently selected color.
  const availabilityBySize = useMemo(() => {
    const map = new Map<string, number>();
    variants
      .filter((variant) => !selectedColorLabel || variant.color === selectedColorLabel)
      .forEach((variant) => {
        const label = variant.size ?? "";
        map.set(label, Math.max(map.get(label) ?? 0, variant.availableQuantity));
      });
    return map;
  }, [variants, selectedColorLabel]);

  // Colors whose every variant is sold out — flagged in the swatch row.
  const soldOutColors = useMemo(() => {
    const byColor = new Map<string, number>();
    variants.forEach((variant) => {
      const label = variant.color ?? "";
      byColor.set(label, Math.max(byColor.get(label) ?? 0, variant.availableQuantity));
    });
    const set = new Set<string>();
    byColor.forEach((qty, label) => {
      if (qty <= 0) set.add(label);
    });
    return set;
  }, [variants]);

  const selectedVariant = useMemo(() => {
    if (variants.length === 0) return null;
    return (
      variants.find(
        (variant) =>
          (!selectedColorLabel || variant.color === selectedColorLabel) &&
          (!selectedSizeLabel || variant.size === selectedSizeLabel),
      ) ?? null
    );
  }, [variants, selectedColorLabel, selectedSizeLabel]);

  // A product is sold out only when no offered size in any color has stock.
  // Individual zero-stock sizes remain visible below, but are disabled and
  // crossed out so customers can distinguish them from a fully sold-out item.
  const productOutOfStock =
    variants.length === 0 || !variants.some((variant) => variant.availableQuantity > 0);
  const selectionUnavailable =
    productOutOfStock || !selectedVariant || selectedVariant.availableQuantity <= 0;

  // Price shown reflects the selected color (colors may be priced differently).
  const displayPrice = useMemo(() => {
    const prices = variants
      .filter((variant) => !selectedColorLabel || variant.color === selectedColorLabel)
      .map((variant) => variant.price);
    return prices.length ? Math.min(...prices) : price;
  }, [variants, selectedColorLabel, price]);

  // compareAtPrice already reflects whichever discount (variant markdown or an
  // active product promotion) is actually being charged - see
  // PromotionService.effectivePrice on the backend. Sourced from the exact
  // selected variant so the badge matches what "Thêm vào giỏ" will charge.
  const compareAtPrice = selectedVariant?.compareAtPrice ?? null;
  const discountPercent =
    compareAtPrice != null && compareAtPrice > displayPrice
      ? Math.round(((compareAtPrice - displayPrice) / compareAtPrice) * 100)
      : null;

  useEffect(() => {
    if (allSizes.length === 0) {
      setSelectedSizeLabel("");
      return;
    }

    const currentOk =
      allSizes.includes(selectedSizeLabel) && (availabilityBySize.get(selectedSizeLabel) ?? 0) > 0;
    if (currentOk) return;

    const firstInStock = allSizes.find((label) => (availabilityBySize.get(label) ?? 0) > 0);
    setSelectedSizeLabel(firstInStock ?? allSizes[0] ?? "");
  }, [allSizes, availabilityBySize, selectedSizeLabel]);

  const decrementQuantity = () => setQuantity((current) => Math.max(1, current - 1));
  const incrementQuantity = () => setQuantity((current) => current + 1);

  const handleCart = async (mode: "cart" | "buy") => {
    if (!selectedVariant) {
      const msg = "Sản phẩm này hiện chưa có biến thể khả dụng.";
      setError(msg);
      toast.error(msg);
      return;
    }

    if (selectedVariant.availableQuantity < quantity) {
      const msg = "Số lượng bạn chọn đang vượt quá tồn kho khả dụng.";
      setError(msg);
      toast.error(msg);
      return;
    }

    setPending(mode);
    setMessage(null);
    setError(null);
    try {
      if (mode === "buy") {
        saveBuyNowSelection({ variantId: selectedVariant.id, quantity });
        setMessage("Đang chuyển sang thanh toán.");
        router.push("/thanh-toan");
      } else {
        await addCartItem(selectedVariant.id, quantity);
        emitSessionChange();
        setMessage("Đã thêm sản phẩm vào giỏ hàng.");
        toast.success("Đã thêm sản phẩm vào giỏ hàng.");
      }
    } catch (err) {
      const msg = getApiErrorMessage(err, "Không thể thêm sản phẩm vào giỏ.");
      setError(msg);
      toast.error(msg);
    } finally {
      setPending(null);
    }
  };

  const handleWishlist = async () => {
    if (!getAccessToken()) {
      router.push("/dang-nhap");
      return;
    }

    setPending("wishlist");
    setMessage(null);
    setError(null);
    try {
      await addWishlistItem(productId);
      emitSessionChange();
      setMessage("Đã thêm sản phẩm vào danh sách yêu thích.");
      toast.success("Đã thêm vào danh sách yêu thích.");
    } catch (err) {
      const msg = getApiErrorMessage(err, "Không thể thêm vào yêu thích.");
      setError(msg);
      toast.error(msg);
    } finally {
      setPending(null);
    }
  };

  return (
    <div className="flex flex-col pt-2">
      <h1 className="mb-3 text-[30px] font-semibold uppercase tracking-[0.04em] text-ivy-dark">{name}</h1>

      <div className="mb-4 flex flex-wrap items-center gap-3 text-[14px] text-ivy-text-muted">
        <span>SKU: {sku}</span>
        <span className="flex items-center gap-0.5" aria-label={`${filledStars} trên 5 sao`}>
          {Array.from({ length: 5 }).map((_, index) => (
            <Star
              key={index}
              size={14}
              className={index < filledStars ? "fill-[#F5A623] text-[#F5A623]" : "fill-ivy-disabled text-ivy-disabled"}
            />
          ))}
        </span>
        <span>({reviewCount} đánh giá)</span>
      </div>

      <div className="mb-6 flex flex-wrap items-center gap-3">
        <span className="text-[30px] font-semibold text-ivy-dark">{displayPrice.toLocaleString("vi-VN")}đ</span>
        {compareAtPrice != null && compareAtPrice > displayPrice ? (
          <span className="text-[16px] text-ivy-text-muted line-through">{compareAtPrice.toLocaleString("vi-VN")}đ</span>
        ) : null}
        {discountPercent ? (
          <span className="rounded-bl-[14px] rounded-tr-[14px] bg-[#E7A746] px-2 py-1 text-[11px] font-semibold text-white">
            -{discountPercent}%
          </span>
        ) : null}
      </div>

      {/* Each product is a single colorway, so color is shown as a per-product label.
          The swatch picker only appears if a product ever carries more than one color. */}
      {selectedColor?.label ? (
        <div className="mb-6 border-t border-ivy-hairline pt-6">
          <div className="text-[14px] font-semibold uppercase tracking-[0.03em] text-ivy-dark">
            Màu sắc: <span className="font-normal normal-case tracking-normal text-ivy-text">{selectedColor.label}</span>
          </div>
          {colors.length > 1 ? (
            <div className="mt-3 flex flex-wrap gap-3">
              {colors.map((color) => {
                const isSoldOut = soldOutColors.has(color.label);
                return (
                  <button
                    key={color.id}
                    type="button"
                    aria-label={isSoldOut ? `${color.label} (hết hàng)` : color.label}
                    aria-pressed={selectedColorLabel === color.label}
                    title={isSoldOut ? `${color.label} - Hết hàng` : color.label}
                    onClick={() => onSelectColor(color.label)}
                    className={cn(
                      "relative h-12 w-12 overflow-hidden rounded-full border border-[#d8d8db] transition",
                      selectedColorLabel === color.label && "ring-2 ring-ivy-dark ring-offset-2",
                      isSoldOut && "opacity-45",
                    )}
                  >
                    <Image
                      src={color.image}
                      alt={color.label}
                      fill
                      sizes="48px"
                      className="object-cover"
                      unoptimized={shouldBypassImageOptimization(color.image)}
                    />
                    {isSoldOut ? (
                      <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
                        <span className="h-[1.5px] w-[150%] rotate-45 bg-ivy-dark/70" />
                      </span>
                    ) : null}
                  </button>
                );
              })}
            </div>
          ) : null}
        </div>
      ) : null}

      <div className="mb-6 border-t border-ivy-hairline pt-6">
        <div className="mb-3 text-[14px] font-semibold uppercase tracking-[0.03em] text-ivy-dark">Kích thước</div>
        <div className="flex flex-wrap gap-3">
          {allSizes.map((label) => {
            const isOutOfStock = (availabilityBySize.get(label) ?? 0) <= 0;
            const isSelected = selectedSizeLabel === label;
            return (
              <button
                key={label}
                type="button"
                disabled={isOutOfStock}
                aria-disabled={isOutOfStock}
                aria-label={isOutOfStock ? `Size ${label}, hết hàng` : `Size ${label}`}
                data-stock-status={isOutOfStock ? "out-of-stock" : "in-stock"}
                data-selected={isSelected}
                onClick={() => setSelectedSizeLabel(label)}
                title={isOutOfStock ? `Size ${label} - Hết hàng` : `Size ${label}`}
                className={cn(
                  "relative h-10 min-w-[50px] overflow-hidden rounded-tl-[14px] rounded-br-[14px] border border-ivy-hairline px-3 text-[13px] uppercase text-ivy-text transition",
                  isSelected && !isOutOfStock && "border-ivy-dark font-semibold text-ivy-dark",
                  isOutOfStock &&
                    "cursor-not-allowed border-[#d7d7da] bg-[#f2f2f3] text-[#aaaeb4] opacity-75",
                )}
              >
                <span className={cn("relative z-10", isOutOfStock && "font-normal")}>{label}</span>
                {isOutOfStock ? (
                  <span
                    aria-hidden
                    className="pointer-events-none absolute left-[-10%] top-1/2 h-px w-[120%] -rotate-[32deg] bg-[#8f9297]"
                  />
                ) : null}
              </button>
            );
          })}
        </div>
        {selectionUnavailable ? (
          <p className="mt-3 text-[13px] font-medium text-[#C62127]">Size/màu bạn chọn hiện đã hết hàng.</p>
        ) : null}
        <button
          type="button"
          onClick={() => setSizeGuideOpen((current) => !current)}
          aria-expanded={sizeGuideOpen}
          className="mt-3 inline-flex items-center gap-2 text-[14px] text-ivy-text underline"
        >
          <Ruler size={16} />
          {sizeGuideOpen ? "Đóng hướng dẫn chọn size" : "Hướng dẫn chọn size"}
        </button>
        {sizeGuideOpen ? (
          <div className="mt-3 rounded-lg bg-[#f7f8f9] p-4 text-[13px] leading-6 text-ivy-text">
            <p className="font-semibold text-ivy-dark">Cách chọn kích thước phù hợp</p>
            <p>Đối chiếu số đo cơ thể với thông số trong phần mô tả sản phẩm. Nếu ở giữa hai size, hãy chọn size lớn hơn để vận động thoải mái.</p>
          </div>
        ) : null}
      </div>

      <div className="mb-7 border-t border-ivy-hairline pt-6">
        <div className="mb-3 text-[14px] font-semibold uppercase tracking-[0.03em] text-ivy-dark">Số lượng</div>
        <div className="flex items-center">
          <button
            type="button"
            aria-label="Giảm số lượng"
            onClick={decrementQuantity}
            className="flex h-10 w-10 items-center justify-center border border-ivy-hairline text-ivy-dark"
          >
            <Minus size={16} />
          </button>
          <input
            type="number"
            name="quantity"
            min={1}
            value={quantity}
            onChange={(event) => {
              const parsed = Number.parseInt(event.target.value, 10);
              setQuantity(Number.isNaN(parsed) ? 1 : Math.max(1, parsed));
            }}
            className="h-10 w-14 border-y border-ivy-hairline text-center text-ivy-dark [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
          />
          <button
            type="button"
            aria-label="Tăng số lượng"
            onClick={incrementQuantity}
            className="flex h-10 w-10 items-center justify-center border border-ivy-hairline text-ivy-dark"
          >
            <Plus size={16} />
          </button>
        </div>
      </div>

      {error ? <p className="mb-4 text-[14px] text-[#C62127]">{error}</p> : null}
      {message ? <p className="mb-4 text-[14px] text-[#257A4D]">{message}</p> : null}
      {productOutOfStock ? (
        <p className="mb-4 border border-[#C62127]/30 bg-[#C62127]/5 px-4 py-3 text-[14px] font-semibold uppercase tracking-[0.04em] text-[#C62127]">
          Hết hàng
        </p>
      ) : null}

      <div className="mb-5 flex flex-col gap-3 md:flex-row">
        <button
          type="button"
          onClick={() => void handleCart("cart")}
          disabled={pending !== null || selectionUnavailable}
          className="h-12 rounded-tl-[18px] rounded-br-[18px] bg-ivy-dark px-8 text-[14px] font-semibold uppercase tracking-[0.03em] text-white disabled:opacity-60"
        >
          {selectionUnavailable ? "Hết hàng" : pending === "cart" ? "Đang thêm..." : "Thêm vào giỏ"}
        </button>
        <button
          type="button"
          onClick={() => void handleCart("buy")}
          disabled={pending !== null || selectionUnavailable}
          className="h-12 rounded-tl-[18px] rounded-br-[18px] border border-ivy-dark px-8 text-[14px] font-semibold uppercase tracking-[0.03em] text-ivy-dark disabled:opacity-60"
        >
          {selectionUnavailable ? "Hết hàng" : pending === "buy" ? "Đang xử lý..." : "Mua hàng"}
        </button>
        <button
          type="button"
          aria-label="Thêm vào yêu thích"
          onClick={() => void handleWishlist()}
          disabled={pending !== null}
          className="flex h-12 w-12 items-center justify-center self-start rounded-tl-[18px] rounded-br-[18px] border border-ivy-dark disabled:opacity-60"
        >
          <HeartIcon size={20} className="text-ivy-dark" />
        </button>
      </div>

      <Link href="/cua-hang" className="text-[14px] text-ivy-dark underline">
        Tìm tại cửa hàng
      </Link>
    </div>
  );
}

