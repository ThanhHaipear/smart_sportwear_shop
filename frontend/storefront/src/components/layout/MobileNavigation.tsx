"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronDown, Menu, X } from "lucide-react";
import type { NavCategoryMenu } from "@/components/layout/types";
import { cn } from "@/lib/utils";

export function MobileNavigation({ items }: { items: NavCategoryMenu[] }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    document.body.style.overflow = open ? "hidden" : "";
    return () => {
      document.body.style.overflow = "";
    };
  }, [open]);

  const closeNavigation = () => {
    setOpen(false);
    setExpanded(null);
  };

  return (
    <div className="md:hidden">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-label={open ? "Đóng menu" : "Mở menu"}
        aria-expanded={open}
        aria-controls="mobile-navigation"
        className="flex size-10 items-center justify-center text-ivy-dark"
      >
        {open ? <X className="size-5" /> : <Menu className="size-5" />}
      </button>

      {open ? (
        <>
          <button
            type="button"
            aria-label="Đóng menu"
            onClick={closeNavigation}
            className="fixed inset-x-0 bottom-0 top-[var(--site-header-main-height)] z-40 bg-black/30"
          />
          <nav
            id="mobile-navigation"
            aria-label="Điều hướng chính"
            className="fixed bottom-0 left-0 top-[var(--site-header-main-height)] z-50 w-[min(88vw,360px)] overflow-y-auto bg-white px-5 py-5 shadow-2xl"
          >
            <ul className="divide-y divide-ivy-hairline">
              {items.map((item) => {
                const groups = item.groups ?? [];
                const quickLinks = item.quickLinks ?? [];
                const hasChildren = groups.length > 0 || quickLinks.length > 0;
                const isExpanded = expanded === item.label;

                return (
                  <li key={item.label} className="py-1">
                    <div className="flex items-center gap-2">
                      <Link
                        href={item.href}
                        onClick={closeNavigation}
                        className={cn(
                          "flex-1 py-3 text-[14px] font-semibold uppercase text-ivy-dark",
                          pathname === item.href && "text-ivy-accent",
                        )}
                      >
                        {item.label}
                      </Link>
                      {hasChildren ? (
                        <button
                          type="button"
                          onClick={() => setExpanded(isExpanded ? null : item.label)}
                          aria-label={`${isExpanded ? "Thu gọn" : "Mở rộng"} ${item.label}`}
                          aria-expanded={isExpanded}
                          className="flex size-10 items-center justify-center"
                        >
                          <ChevronDown className={cn("size-4 transition-transform", isExpanded && "rotate-180")} />
                        </button>
                      ) : null}
                    </div>

                    {isExpanded ? (
                      <div className="pb-4 pl-3">
                        {quickLinks.map((link) => (
                          <Link key={link.label} href={link.href} onClick={closeNavigation} className="block py-2 text-[14px] text-ivy-text">
                            {link.label}
                          </Link>
                        ))}
                        {groups.map((group) => (
                          <div key={group.heading} className="pt-2">
                            <Link href={group.headingHref} onClick={closeNavigation} className="block py-2 text-[13px] font-semibold uppercase text-ivy-dark">
                              {group.heading}
                            </Link>
                            {group.links.map((link) => (
                              <Link key={link.label} href={link.href} onClick={closeNavigation} className="block py-2 pl-3 text-[14px] text-ivy-text">
                                {link.label}
                              </Link>
                            ))}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </nav>
        </>
      ) : null}
    </div>
  );
}
