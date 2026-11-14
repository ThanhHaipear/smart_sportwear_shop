"use client";

import { useDeferredValue, useMemo, useRef, useState } from "react";
import { ApiRequestError } from "@/modules/api/common";
import { fetchAdminUserDetail, updateAdminUserRole, updateAdminUserStatus } from "@/modules/users/browser-api";
import type { AdminUserResponse } from "@/modules/users/types";

const roles = ["CUSTOMER", "SALES_STAFF", "WAREHOUSE_STAFF", "ADMIN"] as const;
const statuses = ["ACTIVE", "LOCKED", "INACTIVE"] as const;

const ROLE_LABELS: Record<string, string> = {
  CUSTOMER: "Khách hàng",
  SALES_STAFF: "Nhân viên bán hàng",
  WAREHOUSE_STAFF: "Nhân viên kho",
  ADMIN: "Quản trị viên"
};

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: "Hoạt động",
  LOCKED: "Bị khóa",
  INACTIVE: "Ngừng hoạt động"
};

function extractError(error: unknown, fallback: string) {
  if (error instanceof ApiRequestError) {
    const payload = error.payload as { message?: string } | null;
    return payload?.message ?? fallback;
  }
  return fallback;
}

export function AdminUsersClient({
  initialUsers,
  mode
}: {
  initialUsers: AdminUserResponse[];
  mode: "customers" | "roles";
}) {
  const [users, setUsers] = useState(initialUsers);
  const [selectedUserId, setSelectedUserId] = useState("");
  const [selectedUser, setSelectedUser] = useState<AdminUserResponse | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [loadingDetailId, setLoadingDetailId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const detailCacheRef = useRef<Record<string, AdminUserResponse>>({});

  const [roleMap, setRoleMap] = useState<Record<string, string>>(() => Object.fromEntries(initialUsers.map((u) => [u.id, u.role])));
  const [statusMap, setStatusMap] = useState<Record<string, string>>(() => Object.fromEntries(initialUsers.map((u) => [u.id, u.status])));

  const filtered = useMemo(() => {
    const q = deferredSearch.trim().toLowerCase();
    if (!q) return users;
    return users.filter((u) => [u.fullName, u.email, u.phone ?? "", u.role, u.status].join(" ").toLowerCase().includes(q));
  }, [deferredSearch, users]);

  async function handleSelect(user: AdminUserResponse) {
    setSelectedUserId(user.id);
    setMessage(null);

    const cached = detailCacheRef.current[user.id];
    if (cached) {
      setSelectedUser(cached);
      return;
    }

    setLoadingDetailId(user.id);
    try {
      const detail = await fetchAdminUserDetail(user.id);
      detailCacheRef.current[user.id] = detail;
      setUsers((curr) => curr.map((item) => (item.id === detail.id ? detail : item)));
      setRoleMap((curr) => ({ ...curr, [detail.id]: detail.role }));
      setStatusMap((curr) => ({ ...curr, [detail.id]: detail.status }));
      setSelectedUser(detail);
    } catch (error) {
      setMessage(extractError(error, "Không tải được chi tiết người dùng."));
      setSelectedUser(user);
    } finally {
      setLoadingDetailId(null);
    }
  }

  async function handleRole(id: string, role: string) {
    const previous = roleMap[id] ?? users.find((u) => u.id === id)?.role ?? "CUSTOMER";
    setRoleMap((curr) => ({ ...curr, [id]: role }));

    try {
      setSavingKey(`${id}:role`);
      setMessage(null);
      const updated = await updateAdminUserRole(id, role);
      detailCacheRef.current[id] = updated;
      setUsers((curr) => curr.map((item) => (item.id === id ? updated : item)));
      setRoleMap((curr) => ({ ...curr, [updated.id]: updated.role }));
      if (selectedUserId === id) setSelectedUser(updated);
      setMessage(`Đã cập nhật vai trò cho ${updated.fullName}.`);
    } catch (error) {
      setRoleMap((curr) => ({ ...curr, [id]: previous }));
      setMessage(extractError(error, "Không cập nhật được vai trò."));
    } finally {
      setSavingKey(null);
    }
  }

  async function handleStatus(id: string, status: string) {
    const previous = statusMap[id] ?? users.find((u) => u.id === id)?.status ?? "ACTIVE";
    setStatusMap((curr) => ({ ...curr, [id]: status }));

    try {
      setSavingKey(`${id}:status`);
      setMessage(null);
      const updated = await updateAdminUserStatus(id, status);
      detailCacheRef.current[id] = updated;
      setUsers((curr) => curr.map((item) => (item.id === id ? updated : item)));
      setStatusMap((curr) => ({ ...curr, [updated.id]: updated.status }));
      if (selectedUserId === id) setSelectedUser(updated);
      setMessage(`Đã cập nhật trạng thái cho ${updated.fullName}.`);
    } catch (error) {
      setStatusMap((curr) => ({ ...curr, [id]: previous }));
      setMessage(extractError(error, "Không cập nhật được trạng thái."));
    } finally {
      setSavingKey(null);
    }
  }

  return (
    <section className="card panel">
      <div className="panel-header">
        <div>
          <h2>{mode === "customers" ? "Danh sách người dùng" : "Người dùng & phân quyền"}</h2>
          <p className="panel-copy">{users.length} người dùng</p>
        </div>
        <input
          className="admin-input"
          style={{ width: 260 }}
          placeholder="Tìm theo tên, email hoặc số điện thoại..."
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>

      {message ? <p className="action-message">{message}</p> : null}

      <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th>Họ tên</th>
            <th>Email</th>
            <th>Vai trò</th>
            <th>Trạng thái</th>
            {mode === "roles" ? <th>Cập nhật</th> : null}
          </tr>
        </thead>
        <tbody>
          {filtered.length === 0 ? (
            <tr>
              <td colSpan={mode === "roles" ? 5 : 4} style={{ textAlign: "center", color: "var(--admin-muted)" }}>
                Không tìm thấy người dùng nào.
              </td>
            </tr>
          ) : null}

          {filtered.map((user) => (
            <tr
              key={user.id}
              className={selectedUserId === user.id ? "row-selected" : ""}
              onClick={() => void handleSelect(user)}
              style={{ cursor: "pointer" }}
            >
              <td>
                <strong>{user.fullName}</strong>
                <div className="table-subtle">
                  {user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString("vi-VN") : "Chưa đăng nhập"}
                </div>
              </td>
              <td>{user.email}</td>
              <td>
                <span className={`status ${user.role === "ADMIN" ? "active" : user.role === "CUSTOMER" ? "draft" : ""}`}>
                  {ROLE_LABELS[user.role] ?? user.role}
                </span>
              </td>
              <td>
                <span className={`status ${user.status === "ACTIVE" ? "active" : user.status === "LOCKED" ? "inactive" : "draft"}`}>
                  {STATUS_LABELS[user.status] ?? user.status}
                </span>
              </td>
              {mode === "roles" ? (
                <td onClick={(event) => event.stopPropagation()}>
                  <div className="admin-inline-form">
                    <select
                      className="select"
                      value={roleMap[user.id] ?? user.role}
                      onChange={(event) => void handleRole(user.id, event.target.value)}
                      disabled={savingKey === `${user.id}:role`}
                    >
                      {roles.map((role) => (
                        <option value={role} key={role}>
                          {ROLE_LABELS[role]}
                        </option>
                      ))}
                    </select>
                    <select
                      className="select"
                      value={statusMap[user.id] ?? user.status}
                      onChange={(event) => void handleStatus(user.id, event.target.value)}
                      disabled={savingKey === `${user.id}:status`}
                    >
                      {statuses.map((status) => (
                        <option value={status} key={status}>
                          {STATUS_LABELS[status]}
                        </option>
                      ))}
                    </select>
                    <span className="table-subtle">
                      {loadingDetailId === user.id
                        ? "Đang tải..."
                        : savingKey === `${user.id}:role` || savingKey === `${user.id}:status`
                          ? "Đang lưu..."
                          : ""}
                    </span>
                  </div>
                </td>
              ) : null}
            </tr>
          ))}
        </tbody>
      </table>
      </div>

      {selectedUser ? (
        <div className="admin-subcard admin-subcard-tight">
          <strong>Chi tiết người dùng</strong>
          <div className="table-subtle">ID: {selectedUser.id}</div>
          <div className="table-subtle">Số điện thoại: {selectedUser.phone ?? "Chưa cập nhật"}</div>
          <div className="table-subtle">Ngày tạo: {new Date(selectedUser.createdAt).toLocaleString("vi-VN")}</div>
          <div className="table-subtle">
            Đăng nhập gần nhất: {selectedUser.lastLoginAt ? new Date(selectedUser.lastLoginAt).toLocaleString("vi-VN") : "Chưa có"}
          </div>
        </div>
      ) : null}
    </section>
  );
}
