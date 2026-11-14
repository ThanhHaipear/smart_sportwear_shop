import Image from "next/image";
import Link from "next/link";
import { SiteHeaderNav } from "@/components/layout/SiteHeaderNav";
import { HeaderActionsLive } from "@/components/layout/HeaderActionsLive";
import { HeaderSearch } from "@/components/layout/HeaderSearch";
import { MobileNavigation } from "@/components/layout/MobileNavigation";
import { buildHeaderNavItems } from "@/modules/category/navigation";
import { fetchCategoryTree } from "@/modules/category/queries";

export async function Header() {
  const navigationItems = buildHeaderNavItems(await fetchCategoryTree());

  return (
    <header className="fixed inset-x-0 top-0 z-40 bg-white">
      <div className="h-[var(--site-header-main-height)] border-b border-ivy-hairline px-4 md:px-12 lg:px-14">
        <div className="grid h-full grid-cols-[auto_1fr_auto] grid-rows-[3.75rem_3rem] items-center gap-x-3 md:grid-cols-[1fr_auto_1fr] md:grid-rows-1 md:gap-4">
          <div className="col-start-1 row-start-1 flex items-center gap-1">
            <MobileNavigation items={navigationItems} />
            <Link href="/" className="flex items-center">
              <Image
                src="/images/logo-v2.png"
                alt="Điểm Đến Thể Thao"
                width={120}
                height={80}
                priority
                className="h-10 w-auto object-contain md:h-18"
              />
            </Link>
          </div>
          <div className="col-span-3 row-start-2 w-full md:col-span-1 md:col-start-2 md:row-start-1">
            <HeaderSearch />
          </div>
          <div className="col-start-3 row-start-1 flex justify-end">
            <HeaderActionsLive />
          </div>
        </div>
      </div>
      <div className="hidden h-[var(--site-header-nav-height)] border-b border-ivy-hairline px-8 md:block md:px-12 lg:px-14">
        <div className="flex h-full items-center">
          <SiteHeaderNav items={navigationItems} />
        </div>
      </div>
    </header>
  );
}

