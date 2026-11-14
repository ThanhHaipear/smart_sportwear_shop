import { AdminUsersClient } from "@/components/users/AdminUsersClient";
import { listAdminUsers } from "@/modules/users/api";

export default async function CustomersPage() {
  const users = await listAdminUsers();

  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>Khách hàng</h1>
          <p>Danh sách người dùng hiện có trong hệ thống, dùng chung API quản trị người dùng.</p>
        </div>
      </section>

      <AdminUsersClient initialUsers={users} mode="customers" />
    </main>
  );
}
