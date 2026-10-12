import { AdminSystemCenterClient } from "@/components/system/AdminSystemCenterClient";
import { listAdminSettings } from "@/modules/settings/api";

export default async function SystemConfigPage() {
  const settings = await listAdminSettings().catch(() => []);

  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>Hệ thống</h1>
          <p>Quản lý các tham số vận hành trong bảng site_settings của schema V2.</p>
        </div>
      </section>

      <AdminSystemCenterClient initialSettings={settings} />
    </main>
  );
}
