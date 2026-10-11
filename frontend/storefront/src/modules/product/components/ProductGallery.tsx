"use client";

import { useState } from "react";
import Image from "next/image";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { shouldBypassImageOptimization } from "@/lib/image";

interface ProductGalleryProps {
  images: string[];
  alt: string;
}

export function ProductGallery({ images, alt }: ProductGalleryProps) {
  const [activeIndex, setActiveIndex] = useState(0);

  const goToPrev = () => setActiveIndex((prev) => (prev - 1 + images.length) % images.length);
  const goToNext = () => setActiveIndex((prev) => (prev + 1) % images.length);

  if (images.length === 0) return null;

  return (
    <div className="flex flex-col gap-4 md:flex-row md:gap-5">
      <div className="hidden w-[90px] shrink-0 flex-col gap-4 overflow-y-auto md:flex">
        {images.map((image, index) => (
          <button
            key={`${image}-${index}`}
            type="button"
            onClick={() => setActiveIndex(index)}
            aria-label={`${alt} thumbnail ${index + 1}`}
            aria-current={index === activeIndex}
            className={cn(
              "relative aspect-square overflow-hidden border border-transparent bg-[#f7f7f7]",
              index === activeIndex && "border-ivy-dark",
            )}
          >
            <Image
              src={image}
              alt={`${alt} thumbnail ${index + 1}`}
              fill
              sizes="90px"
              className="object-contain p-1"
              unoptimized={shouldBypassImageOptimization(image)}
            />
          </button>
        ))}
      </div>

      <div className="relative flex-1 overflow-hidden bg-white">
        {images.length > 1 ? (
          <button
            type="button"
            onClick={goToPrev}
            aria-label="Ảnh trước"
            className="absolute left-4 top-1/2 z-10 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded-full bg-white/85 text-ivy-dark shadow-sm transition hover:bg-white"
          >
            <ChevronLeft className="size-5" />
          </button>
        ) : null}

        <div className="relative aspect-square w-full overflow-hidden bg-[#f7f7f7]">
          <Image
            key={activeIndex}
            src={images[activeIndex]}
            alt={alt}
            fill
            sizes="(min-width: 1024px) 620px, 100vw"
            className="object-contain p-3 md:p-6"
            priority
            unoptimized={shouldBypassImageOptimization(images[activeIndex])}
          />
        </div>

        {images.length > 1 ? (
          <button
            type="button"
            onClick={goToNext}
            aria-label="Ảnh tiếp theo"
            className="absolute right-4 top-1/2 z-10 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded-full bg-white/85 text-ivy-dark shadow-sm transition hover:bg-white"
          >
            <ChevronRight className="size-5" />
          </button>
        ) : null}
      </div>
    </div>
  );
}
