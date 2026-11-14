"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { clearAuthSession } from "@/modules/auth/session";

export default function GlobalError({
  error,
  reset
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const router = useRouter();

  useEffect(() => {
    console.error(error);
  }, [error]);

  const normalizedMessage = error.message?.toLowerCase() ?? "";
  const isAuthError = normalizedMessage.includes("unauthorized") || normalizedMessage.includes("401");
  const isNetworkError =
    normalizedMessage.includes("fetch failed") ||
    normalizedMessage.includes("econnrefused") ||
    normalizedMessage.includes("network");

  function handleRecovery() {
    if (isAuthError) {
      clearAuthSession();
      router.replace("/login");
      router.refresh();
      return;
    }
    reset();
  }

  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        minHeight: "60vh",
        gap: 16,
        padding: 32,
        textAlign: "center"
      }}
    >
      <div style={{ fontSize: 48 }}>⚠️</div>
      <h2 style={{ fontSize: "1.25rem", fontWeight: 700, margin: 0 }}>
        {isAuthError ? "Phiên đăng nhập không còn hợp lệ" : isNetworkError ? "Không kết nối được backend" : "Trang bị lỗi"}
      </h2>
      <p style={{ color: "var(--text-muted)", margin: 0, maxWidth: 420 }}>
        {isAuthError
          ? "Vui lòng đăng nhập lại để tiếp tục sử dụng trang quản trị."
          : isNetworkError
            ? "Không thể kết nối tới API gateway tại localhost:8085. Vui lòng kiểm tra các dịch vụ backend rồi thử lại."
            : error.message ?? "Đã xảy ra lỗi không mong muốn."}
      </p>
      <button
        className="admin-btn"
        type="button"
        onClick={handleRecovery}
        style={{ marginTop: 8 }}
      >
        {isAuthError ? "Đăng nhập lại" : "Thử lại"}
      </button>
    </div>
  );
}
