import Link from "next/link";
import { cn } from "@/lib/utils";
import { LIFESTYLE_NAV_ITEMS } from "@/modules/content/data/editorial";
import type { LifestyleSlug } from "@/modules/content/types";

interface LifestyleHeaderProps {
  activeSlug: LifestyleSlug;
}

export function LifestyleHeader({ activeSlug }: LifestyleHeaderProps) {
  return (
    <div className="py-10 text-center">
      <h1 className="mb-8 text-4xl font-extrabold tracking-[2px] text-ivy-dark uppercase md:text-[56px]">
        TIN THỂ THAO
      </h1>
      <nav className="mb-10 flex justify-start gap-8 overflow-x-auto border-b border-ivy-hairline whitespace-nowrap md:justify-center">
        {LIFESTYLE_NAV_ITEMS.map((item) => {
          const isActive = item.slug === activeSlug;
          return (
            <Link
              key={item.slug}
              href={item.href}
              className={cn(
                "pb-2 text-sm font-semibold tracking-[1px]",
                isActive ? "border-b-2 border-ivy-dark text-ivy-dark" : "text-ivy-text-muted",
              )}
            >
              {item.label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
