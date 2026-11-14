import { AdminUsersClient } from "@/components/users/AdminUsersClient";
import { listAdminUsers } from "@/modules/users/api";

export default async function UsersRolesPage() {
  const users = await listAdminUsers();

  return (
    <main className="workspace">
      <section className="page-title">
        <div>
          <h1>Phân quyền</h1>
          <p>Rà soát vai trò và trạng thái tài khoản từ API quản trị người dùng.</p>
        </div>
      </section>

      <AdminUsersClient initialUsers={users} mode="roles" />
    </main>
  );
}
