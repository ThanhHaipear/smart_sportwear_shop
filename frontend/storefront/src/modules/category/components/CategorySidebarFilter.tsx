"use client";

import { startTransition, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Plus, Minus } from "lucide-react";
import { cn } from "@/lib/utils";

interface ColorSwatch {
  label: string;
  value: string;
  hex: string;
}

const CLOTH_SIZES = ["XS", "S", "M", "L", "XL", "XXL"] as const;
const SHOE_SIZES = ["36", "37", "38", "39", "40", "41", "42", "43", "44", "45"] as const;

const COLOR_SWATCHES: ColorSwatch[] = [
  { label: "Đen", value: "BLACK", hex: "#1A1A1A" },
  { label: "Trắng", value: "WHITE", hex: "#F5F5F5" },
  { label: "Xanh dương", value: "BLUE", hex: "#1565C0" },
  { label: "Vàng", value: "YELLOW", hex: "#F5C518" },
  { label: "Hồng", value: "PINK", hex: "#E91E8C" },
  { label: "Đỏ", value: "RED", hex: "#8B0000" },
  { label: "Xám", value: "GRAY", hex: "#757575" },
  { label: "Nâu", value: "BROWN", hex: "#5D4037" },
  { label: "Xanh lá", value: "GREEN", hex: "#2E7D32" },
  { label: "Cam", value: "ORANGE", hex: "#E8813A" },
  { label: "Tím", value: "PURPLE", hex: "#9C27B0" },
];

const DISCOUNT_OPTIONS = [
  { label: "Dưới 30%", value: "lt30" },
  { label: "30% - 50%", value: "30to50" },
  { label: "Trên 50%", value: "gt50" },
] as const;

const SURFACE_OPTIONS = [
  { label: "Cỏ tự nhiên (FG)", value: "FG" },
  { label: "Cỏ nhân tạo (TF/AG)", value: "TF" },
  { label: "Futsal (IC)", value: "IC" },
] as const;

const GENDER_OPTIONS = [
  { label: "Nam", value: "MEN" },
  { label: "Nữ", value: "WOMEN" },
  { label: "Unisex", value: "UNISEX" },
] as const;

const SPORT_OPTIONS = [
  { label: "Bóng đá", value: "FOOTBALL" },
  { label: "Chạy bộ", value: "RUNNING" },
] as const;

const BRAND_OPTIONS = [
  { label: "Nike", value: "nike" },
  { label: "Adidas", value: "adidas" },
] as const;

type SizeType = "cloth" | "shoe" | "both" | "none";

const MIN_PRICE = 0;
const MAX_PRICE = 10_000_000;

function formatVnd(value: number) {
  return `${value.toLocaleString("vi-VN")}đ`;
}

function SizeChip({
  size,
  selected,
  onToggle,
}: {
  size: string;
  selected: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={selected}
      className={cn(
        "flex h-9 min-w-10 items-center justify-center rounded-md border px-3 text-[13px] transition-colors",
        selected
          ? "border-ivy-dark bg-ivy-dark text-white"
          : "border-gray-200 text-gray-600 hover:border-gray-400",
      )}
    >
      {size}
    </button>
  );
}

function FilterSection({
  label,
  open,
  onToggle,
  children,
}: {
  label: string;
  open: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="border-b border-gray-100 py-4">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center justify-between text-[14px] font-semibold uppercase tracking-wider text-gray-800"
      >
        <span>{label}</span>
        {open ? <Minus className="size-3.5 shrink-0 text-gray-500" /> : <Plus className="size-3.5 shrink-0 text-gray-500" />}
      </button>
      {open && <div className="mt-4">{children}</div>}
    </div>
  );
}

export function CategorySidebarFilter({
  initialSize,
  initialColor,
  initialMinPrice,
  initialMaxPrice,
  initialSurface,
  initialDiscount,
  initialGender,
  initialSportType,
  initialBrandSlug,
  showSurface = false,
  sizeType = "both",
}: {
  initialSize?: string;
  initialColor?: string;
  initialMinPrice?: number;
  initialMaxPrice?: number;
  initialSurface?: string;
  initialDiscount?: string;
  initialGender?: string;
  initialSportType?: string;
  initialBrandSlug?: string;
  showSurface?: boolean;
  sizeType?: SizeType;
}) {
  const [openSurface, setOpenSurface] = useState(Boolean(initialSurface));
  const [openSize, setOpenSize] = useState(false);
  const [openColor, setOpenColor] = useState(false);
  const [openPrice, setOpenPrice] = useState(false);
  const [openDiscount, setOpenDiscount] = useState(false);
  const [openGender, setOpenGender] = useState(Boolean(initialGender));
  const [openSport, setOpenSport] = useState(Boolean(initialSportType));
  const [openBrand, setOpenBrand] = useState(Boolean(initialBrandSlug));
  const [selectedSurface, setSelectedSurface] = useState<string | null>(initialSurface ?? null);

  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [pendingUrl, setPendingUrl] = useState<string | null>(null);

  const [selectedSize, setSelectedSize] = useState<string | null>(initialSize ?? null);
  const [selectedColor, setSelectedColor] = useState<string | null>(initialColor ?? null);
  const [selectedDiscount, setSelectedDiscount] = useState<string | null>(initialDiscount ?? null);
  const [selectedGender, setSelectedGender] = useState<string | null>(initialGender ?? null);
  const [selectedSportType, setSelectedSportType] = useState<string | null>(initialSportType ?? null);
  const [selectedBrandSlug, setSelectedBrandSlug] = useState<string | null>(initialBrandSlug ?? null);
  const [priceRange, setPriceRange] = useState<[number, number]>([
    initialMinPrice ?? MIN_PRICE,
    initialMaxPrice ?? MAX_PRICE,
  ]);

  const currentQuery = searchParams.toString();
  const currentUrl = currentQuery ? `${pathname}?${currentQuery}` : pathname;
  const isPending = pendingUrl !== null && pendingUrl !== currentUrl;

  function handleMinChange(value: number) {
    setPriceRange(([, max]) => [Math.min(value, max), max]);
  }

  function handleMaxChange(value: number) {
    setPriceRange(([min]) => [min, Math.max(value, min)]);
  }

  function applyFilters() {
    const params = new URLSearchParams(searchParams.toString());
    params.delete("page");

    if (selectedSize) params.set("size", selectedSize);
    else params.delete("size");

    if (selectedColor) params.set("color", selectedColor);
    else params.delete("color");

    if (selectedSurface) params.set("surface", selectedSurface);
    else params.delete("surface");

    if (priceRange[0] > MIN_PRICE) params.set("minPrice", String(priceRange[0]));
    else params.delete("minPrice");

    if (priceRange[1] < MAX_PRICE) params.set("maxPrice", String(priceRange[1]));
    else params.delete("maxPrice");

    if (selectedDiscount) params.set("discount", selectedDiscount);
    else params.delete("discount");

    if (selectedGender) params.set("gender", selectedGender);
    else params.delete("gender");

    if (selectedSportType) params.set("sportType", selectedSportType);
    else params.delete("sportType");

    if (selectedBrandSlug) params.set("brandSlug", selectedBrandSlug);
    else params.delete("brandSlug");

    const query = params.toString();
    const nextUrl = query ? `${pathname}?${query}` : pathname;
    if (nextUrl === currentUrl) return;
    setPendingUrl(nextUrl);
    startTransition(() => {
      router.push(nextUrl, { scroll: false });
    });
  }

  function clearFilters() {
    const params = new URLSearchParams(searchParams.toString());
    ["page", "size", "color", "minPrice", "maxPrice", "surface", "discount", "gender", "sportType", "brandSlug"].forEach((k) => params.delete(k));
    setSelectedSize(null);
    setSelectedColor(null);
    setSelectedSurface(null);
    setPriceRange([MIN_PRICE, MAX_PRICE]);
    setSelectedDiscount(null);
    setSelectedGender(null);
    setSelectedSportType(null);
    setSelectedBrandSlug(null);
    const query = params.toString();
    const nextUrl = query ? `${pathname}?${query}` : pathname;
    if (nextUrl === currentUrl) return;
    setPendingUrl(nextUrl);
    startTransition(() => {
      router.push(nextUrl, { scroll: false });
    });
  }

  const showCloth = sizeType === "cloth" || sizeType === "both";
  const showShoe = sizeType === "shoe" || sizeType === "both";

  return (
    <aside className="w-full lg:w-64 lg:shrink-0">
      <FilterSection label="Giới tính" open={openGender} onToggle={() => setOpenGender((p) => !p)}>
        <ul className="space-y-3">
          {GENDER_OPTIONS.map((opt) => (
            <li key={opt.value}>
              <label className="flex cursor-pointer items-center gap-2 text-[13px] text-gray-700">
                <input
                  type="radio"
                  name="gender"
                  checked={selectedGender === opt.value}
                  onChange={() => setSelectedGender(opt.value)}
                  className="size-4 accent-ivy-dark"
                />
                {opt.label}
              </label>
            </li>
          ))}
        </ul>
      </FilterSection>

      <FilterSection label="Môn thể thao" open={openSport} onToggle={() => setOpenSport((p) => !p)}>
        <ul className="space-y-3">
          {SPORT_OPTIONS.map((opt) => (
            <li key={opt.value}>
              <label className="flex cursor-pointer items-center gap-2 text-[13px] text-gray-700">
                <input
                  type="radio"
                  name="sportType"
                  checked={selectedSportType === opt.value}
                  onChange={() => setSelectedSportType(opt.value)}
                  className="size-4 accent-ivy-dark"
                />
                {opt.label}
              </label>
            </li>
          ))}
        </ul>
      </FilterSection>

      <FilterSection label="Thương hiệu" open={openBrand} onToggle={() => setOpenBrand((p) => !p)}>
        <ul className="space-y-3">
          {BRAND_OPTIONS.map((opt) => (
            <li key={opt.value}>
              <label className="flex cursor-pointer items-center gap-2 text-[13px] text-gray-700">
                <input
                  type="radio"
                  name="brandSlug"
                  checked={selectedBrandSlug === opt.value}
                  onChange={() => setSelectedBrandSlug(opt.value)}
                  className="size-4 accent-ivy-dark"
                />
                {opt.label}
              </label>
            </li>
          ))}
        </ul>
      </FilterSection>

      {showSurface ? (
        <FilterSection label="Mặt sân" open={openSurface} onToggle={() => setOpenSurface((p) => !p)}>
          <ul className="space-y-3">
            {SURFACE_OPTIONS.map((opt) => (
              <li key={opt.value}>
                <label className="flex cursor-pointer items-center gap-2 text-[13px] text-gray-700">
                  <input
                    type="radio"
                    name="surface"
                    checked={selectedSurface === opt.value}
                    onChange={() => setSelectedSurface(opt.value)}
                    className="size-4 accent-ivy-dark"
                  />
                  {opt.label}
                </label>
              </li>
            ))}
          </ul>
        </FilterSection>
      ) : null}

      {sizeType !== "none" ? (
        <FilterSection label="Size" open={openSize} onToggle={() => setOpenSize((p) => !p)}>
          <div className="space-y-3">
            {showCloth && (
              <div>
                {sizeType === "both" && (
                  <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-gray-400">Quần áo</p>
                )}
                <div className="flex flex-wrap gap-2">
                  {CLOTH_SIZES.map((s) => (
                    <SizeChip key={s} size={s} selected={selectedSize === s} onToggle={() => setSelectedSize((p) => (p === s ? null : s))} />
                  ))}
                </div>
              </div>
            )}
            {showShoe && (
              <div>
                {sizeType === "both" && (
                  <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-gray-400">Giày</p>
                )}
                <div className="flex flex-wrap gap-2">
                  {SHOE_SIZES.map((s) => (
                    <SizeChip key={s} size={s} selected={selectedSize === s} onToggle={() => setSelectedSize((p) => (p === s ? null : s))} />
                  ))}
                </div>
              </div>
            )}
          </div>
        </FilterSection>
      ) : null}

      <FilterSection label="Màu sắc" open={openColor} onToggle={() => setOpenColor((p) => !p)}>
        <div className="flex flex-wrap gap-3">
          {COLOR_SWATCHES.map((swatch) => {
            const checked = selectedColor === swatch.value;
            return (
              <button
                key={swatch.label}
                type="button"
                title={swatch.label}
                aria-label={swatch.label}
                aria-pressed={checked}
                onClick={() => setSelectedColor((p) => (p === swatch.value ? null : swatch.value))}
                className={cn(
                  "size-5 rounded-full border border-gray-200 transition-transform",
                  checked && "ring-2 ring-ivy-dark ring-offset-2 scale-110",
                )}
                style={{ backgroundColor: swatch.hex }}
              />
            );
          })}
        </div>
      </FilterSection>

      <FilterSection label="Mức giá" open={openPrice} onToggle={() => setOpenPrice((p) => !p)}>
        <div>
          <div className="relative h-0.5 rounded-full bg-gray-200">
            <div
              className="absolute h-0.5 rounded-full bg-ivy-dark"
              style={{
                left: `${(priceRange[0] / MAX_PRICE) * 100}%`,
                right: `${100 - (priceRange[1] / MAX_PRICE) * 100}%`,
              }}
            />
          </div>
          <div className="relative mt-3 h-4">
            <input
              type="range"
              min={MIN_PRICE}
              max={MAX_PRICE}
              step={100_000}
              value={priceRange[0]}
              onChange={(e) => handleMinChange(Number(e.target.value))}
              aria-label="Mức giá tối thiểu"
              className="pointer-events-auto absolute inset-x-0 top-0 w-full accent-ivy-dark"
            />
            <input
              type="range"
              min={MIN_PRICE}
              max={MAX_PRICE}
              step={100_000}
              value={priceRange[1]}
              onChange={(e) => handleMaxChange(Number(e.target.value))}
              aria-label="Mức giá tối đa"
              className="pointer-events-auto absolute inset-x-0 top-0 w-full accent-ivy-dark"
            />
          </div>
          <div className="mt-3 flex items-center justify-between text-[12px] text-gray-500">
            <span>{formatVnd(priceRange[0])}</span>
            <span>{formatVnd(priceRange[1])}</span>
          </div>
        </div>
      </FilterSection>

      <FilterSection label="Mức chiết khấu" open={openDiscount} onToggle={() => setOpenDiscount((p) => !p)}>
        <ul className="space-y-3">
          {DISCOUNT_OPTIONS.map((opt) => (
            <li key={opt.value}>
              <label className="flex cursor-pointer items-center gap-2 text-[13px] text-gray-700">
                <input
                  type="radio"
                  name="discount"
                  checked={selectedDiscount === opt.value}
                  onChange={() => setSelectedDiscount(opt.value)}
                  className="size-4 accent-ivy-dark"
                />
                {opt.label}
              </label>
            </li>
          ))}
        </ul>
      </FilterSection>

      <div className="mt-6 flex gap-3">
        <button
          type="button"
          onClick={clearFilters}
          disabled={isPending}
          className="h-10 flex-1 rounded-full border border-ivy-dark text-[13px] font-semibold uppercase tracking-wider text-ivy-dark hover:bg-gray-50 disabled:cursor-wait disabled:opacity-70"
        >
          Bỏ lọc
        </button>
        <button
          type="button"
          onClick={applyFilters}
          disabled={isPending}
          className="h-10 flex-1 rounded-full bg-ivy-dark text-[13px] font-semibold uppercase tracking-wider text-white hover:opacity-90 disabled:cursor-wait disabled:opacity-70"
        >
          Lọc
        </button>
      </div>
    </aside>
  );
}
