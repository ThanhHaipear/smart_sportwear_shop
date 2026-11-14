import { AdminBrandsClient } from "@/components/catalog/AdminBrandsClient";
import { listAdminBrands } from "@/modules/catalog-admin/api";

export default async function BrandsPage() {
  const brands = await listAdminBrands();

  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>Thương hiệu</h1>
          <p>Quản lý thương hiệu hiển thị trên storefront và dùng chung cho toàn bộ catalog sản phẩm.</p>
        </div>
      </section>

      <AdminBrandsClient initialItems={brands} />
    </main>
  );
}
