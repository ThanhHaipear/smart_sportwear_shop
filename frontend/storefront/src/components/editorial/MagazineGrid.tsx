import Image from "next/image";
import Link from "next/link";
import { MAGAZINE_ITEMS } from "@/modules/content/data/editorial";

export function MagazineGrid() {
  return (
    <div className="mb-12 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
      {MAGAZINE_ITEMS.map((item) => (
        <Link key={item.image} href={item.href} className="block">
          <div className="relative mb-4 aspect-4/3">
            <Image src={item.image} alt={item.title} fill className="object-cover" />
          </div>
          <div>
            <p className="mb-2 text-[13px] leading-5 text-ivy-text">{item.caption}</p>
            <p className="mb-2 text-[15px] font-bold leading-[22px] text-ivy-dark">{item.title}</p>
            <p className="text-xs text-ivy-price-old">{item.date}</p>
          </div>
        </Link>
      ))}
    </div>
  );
}
