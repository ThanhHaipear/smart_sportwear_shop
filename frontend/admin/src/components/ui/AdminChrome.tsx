"use client";

import {
  ArrowCounterClockwise,
  Books,
  ChartLineUp,
  FileText,
  Flag,
  GearSix,
  House,
  ImageSquare,
  MagnifyingGlass,
  Package,
  Receipt,
  Scroll,
  ShieldCheck,
  SignOut,
  Star,
  Ticket,
  TreeStructure,
  TShirt,
  Truck,
  Users,
} from "@phosphor-icons/react";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { adminLogout } from "@/modules/auth/api";
import { clearAuthSession, getBrowserRefreshToken, getBrowserUserRole } from "@/modules/auth/session";
import { AdminNotificationBell } from "@/components/ui/AdminNotificationBell";

type NavItem = {
  label: string;
  href: string;
  icon: typeof House;
};

type NavGroup = {
  title: string;
  items: NavItem[];
};

const navGroups: NavGroup[] = [
  {
    title: "Điều hành",
    items: [
      { label: "Tổng quan", href: "/", icon: House },
      { label: "Đơn hàng", href: "/orders", icon: Receipt },
      { label: "Tồn kho", href: "/inventory", icon: Package },
      { label: "Visual Search", href: "/visual-search", icon: ImageSquare },
      { label: "Khách hàng", href: "/customers", icon: Users },
      { label: "Báo cáo", href: "/reports", icon: ChartLineUp },
      { label: "Đổi trả", href: "/returns", icon: ArrowCounterClockwise },
      { label: "Đánh giá", href: "/reviews", icon: Star },
    ],
  },
  {
    title: "Catalog",
    items: [
      { label: "Sản phẩm", href: "/products", icon: TShirt },
      { label: "Danh mục", href: "/categories", icon: TreeStructure },
      { label: "Thương hiệu", href: "/brands", icon: Flag },
      { label: "Bộ sưu tập", href: "/collections", icon: Books },
      { label: "Banner", href: "/banners", icon: ImageSquare },
      { label: "Combo", href: "/combos", icon: Ticket },
      { label: "Khuyến mãi", href: "/promotions", icon: Ticket },
    ],
  },
  {
    title: "Hệ thống",
    items: [
      { label: "Nội dung", href: "/pages", icon: FileText },
      { label: "Nhật ký", href: "/audit-logs", icon: Scroll },
      { label: "Phân quyền", href: "/users-roles", icon: ShieldCheck },
      { label: "Cấu hình", href: "/system-config", icon: GearSix },
    ],
  },
];

function getPageMeta(pathname: string) {
  const metaByPrefix = [
    { prefix: "/orders", title: "Đơn hàng", subtitle: "Theo dõi xử lý đơn, giao vận và tiến độ chăm sóc khách hàng." },
    { prefix: "/visual-search", title: "Visual Search", subtitle: "Theo dõi coverage embedding, usage AI và hàng đợi đồng bộ ảnh." },
    { prefix: "/inventory", title: "Tồn kho", subtitle: "Kiểm soát nhập xuất, điều chỉnh và cảnh báo SKU tồn thấp." },
    { prefix: "/products", title: "Sản phẩm", subtitle: "Quản lý thông tin sản phẩm, biến thể, ảnh và bộ sưu tập." },
    { prefix: "/categories", title: "Danh mục", subtitle: "Sắp xếp hệ thống danh mục để storefront và tìm kiếm rõ ràng hơn." },
    { prefix: "/brands", title: "Thương hiệu", subtitle: "Tập trung quản trị thương hiệu, nhận diện và trạng thái hiển thị." },
    { prefix: "/collections", title: "Bộ sưu tập", subtitle: "Tạo các cụm trưng bày theo mùa, chiến dịch và dòng sản phẩm." },
    { prefix: "/banners", title: "Banner", subtitle: "Điều phối hero banner, vị trí hiển thị và nội dung chiến dịch." },
    { prefix: "/combos", title: "Combo", subtitle: "Tạo bộ sản phẩm mua kèm và mức giảm cố định khi mua đủ bộ." },
    { prefix: "/promotions", title: "Khuyến mãi", subtitle: "Tạo chương trình giảm giá theo sản phẩm, phần trăm và khung thời gian hiệu lực." },
    { prefix: "/customers", title: "Khách hàng", subtitle: "Theo dõi tình trạng tài khoản và phân loại người dùng." },
    { prefix: "/pages", title: "Nội dung", subtitle: "Biên tập các trang tĩnh và nội dung truyền thông của hệ thống." },
    { prefix: "/reports", title: "Báo cáo", subtitle: "Xem snapshot kinh doanh theo thời gian thực từ backend." },
    { prefix: "/audit-logs", title: "Nhật ký", subtitle: "Theo dõi mọi thao tác nhạy cảm để kiểm soát vận hành." },
    { prefix: "/reviews", title: "Đánh giá", subtitle: "Duyệt, ẩn hoặc xử lý nội dung review từ khách hàng." },
    { prefix: "/returns", title: "Đổi trả", subtitle: "Xử lý hoàn hàng, hoàn tiền và các case hậu mãi." },
    { prefix: "/users-roles", title: "Phân quyền", subtitle: "Điều chỉnh vai trò nội bộ và quyền vận hành hệ thống." },
    { prefix: "/system-config", title: "Cấu hình", subtitle: "Quản lý mẫu thông báo, cấu hình và các công cụ hệ thống." },
  ];

  return metaByPrefix.find((item) => pathname.startsWith(item.prefix)) ?? {
    title: "Tổng quan",
    subtitle: "Bảng điều hành trung tâm cho toàn bộ vận hành của cửa hàng.",
  };
}

export function Sidebar() {
  const pathname = usePathname();
  const [role, setRole] = useState<string | null>(null);

  useEffect(() => {
    setRole(getBrowserUserRole());
  }, []);

  return (
    <aside className="sidebar">
      <div className="admin-brand">
        <div className="admin-brand-logo">
          <Image src="/logo.png" alt="Điểm Đến Thể Thao" width={64} height={64} priority />
        </div>
        <div className="admin-brand-text">
          <strong>Điểm Đến</strong>
          <span>Sport Admin</span>
        </div>
      </div>

      <div className="sidebar-summary">
        <span className="sidebar-summary-label">Hệ thống quản trị</span>
        <p>Điều phối đơn hàng, kho, catalog và cấu hình vận hành trong một nơi.</p>
      </div>

      <nav className="side-nav" aria-label="Admin">
        {navGroups.map((group) => (
          <div key={group.title} className="side-nav-group">
            <p className="side-nav-title">{group.title}</p>
            <div className="side-nav-list">
              {group.items.filter((item) => {
                return item.href !== "/inventory" || role !== "SALES_STAFF";
              }).map((item) => {
                const Icon = item.icon;
                const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);

                return (
                  <Link className={active ? "active" : ""} href={item.href} key={item.label} prefetch={false}>
                    <Icon size={18} weight="duotone" />
                    <span>{item.label}</span>
                  </Link>
                );
              })}
            </div>
          </div>
        ))}
      </nav>
    </aside>
  );
}

export function AdminTopbar() {
  const router = useRouter();
  const pathname = usePathname();
  const pageMeta = getPageMeta(pathname);
  const [searchTerm, setSearchTerm] = useState("");

  useEffect(() => {
    function syncSearchTerm() {
      const keyword = pathname.startsWith("/products")
        ? new URLSearchParams(window.location.search).get("q") ?? ""
        : "";
      setSearchTerm(keyword);
    }

    syncSearchTerm();
    window.addEventListener("popstate", syncSearchTerm);
    return () => window.removeEventListener("popstate", syncSearchTerm);
  }, [pathname]);

  function handleSearch(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const params = new URLSearchParams();
    const keyword = searchTerm.trim();

    if (keyword) {
      params.set("q", keyword);
    } else {
      params.delete("q");
    }

    const query = params.toString();
    router.push(`/products${query ? `?${query}` : ""}`);
  }

  async function handleLogout() {
    const refreshToken = getBrowserRefreshToken();
    if (refreshToken) {
      try {
        await adminLogout(refreshToken);
      } catch {
      }
    }
    clearAuthSession();
    router.push("/login");
    router.refresh();
  }

  return (
    <header className="admin-topbar">
      <div className="admin-topbar-main">
        <div className="admin-page-intro">
          <span className="admin-page-kicker">Admin Console</span>
          <strong>{pageMeta.title}</strong>
          <p>{pageMeta.subtitle}</p>
        </div>
        <form className="admin-search" role="search" onSubmit={handleSearch}>
          <MagnifyingGlass size={20} />
          <input
            aria-label="Tìm kiếm sản phẩm"
            placeholder="Tìm theo tên, SKU, danh mục hoặc thương hiệu..."
            value={searchTerm}
            onChange={(event) => setSearchTerm(event.target.value)}
          />
        </form>
      </div>

      <div className="admin-actions">
        <span className="admin-badge">Đồng bộ thời gian thực</span>
        <AdminNotificationBell />
        <button className="admin-btn">
          <Truck size={18} weight="duotone" />
          Xuất báo cáo
        </button>
        <button className="admin-btn secondary" onClick={handleLogout} aria-label="Đăng xuất">
          <SignOut size={18} weight="duotone" />
          Đăng xuất
        </button>
      </div>
    </header>
  );
}
