import { Wrench } from "@phosphor-icons/react/dist/ssr";

const sectionTitles: Record<string, string> = {
  orders: "Đơn hàng",
  inventory: "Tồn kho",
  customers: "Khách hàng",
  reports: "Báo cáo",
  "users-roles": "Phân quyền",
  "system-config": "Hệ thống",
};

type AdminSectionPageProps = {
  params: Promise<{ section: string }>;
};

export default async function AdminSectionPage({ params }: AdminSectionPageProps) {
  const { section } = await params;
  const title = sectionTitles[section] ?? "Module";

  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>{title}</h1>
          <p>Module giữ chỗ theo cùng layout THF Admin để điều hướng không bị lỗi 404.</p>
        </div>
      </section>

      <section className="card panel module-placeholder">
        <Wrench size={34} weight="duotone" />
        <div>
          <h2>Đang chuẩn bị module</h2>
          <p>Trang quản trị này sẽ dùng chung sidebar, topbar, bảng dữ liệu và trạng thái rỗng theo phong cách storefront.</p>
        </div>
      </section>
    </main>
  );
}
