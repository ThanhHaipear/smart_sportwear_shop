"use client";

import { SoccerBall } from "@phosphor-icons/react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { adminLogin } from "@/modules/auth/api";
import { persistAuthSession } from "@/modules/auth/session";

const ALLOWED_ROLES = ["ADMIN", "SALES_STAFF", "WAREHOUSE_STAFF"];

export function AdminLoginForm() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const data = await adminLogin(email, password);

      if (!ALLOWED_ROLES.includes(data.user.role)) {
        setError("Tài khoản không có quyền truy cập trang quản trị");
        return;
      }

      persistAuthSession(data.tokens, data.user.role);
      router.push("/");
      router.refresh();
    } catch (err: unknown) {
      if (
        err !== null &&
        typeof err === "object" &&
        "status" in err &&
        (err as { status: number }).status === 401
      ) {
        setError("Email hoặc mật khẩu không đúng");
      } else if (err instanceof Error) {
        setError(err.message || "Đăng nhập thất bại, vui lòng thử lại");
      } else {
        setError("Đăng nhập thất bại, vui lòng thử lại");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-page">
      <div className="login-card card">
        <div className="login-brand">
          <span className="login-logo">
            <SoccerBall size={24} weight="fill" />
          </span>
          <strong>DHQ Admin</strong>
        </div>
        <h1 className="login-heading">Đăng nhập</h1>
        <p className="login-sub">Dành cho nhân viên DHQ</p>

        <form className="login-form" onSubmit={handleSubmit} noValidate>
          <div className="login-field">
            <label htmlFor="email" className="login-label">
              Email
            </label>
            <input
              id="email"
              type="email"
              className="admin-input"
              placeholder="name@dhq.vn"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              disabled={loading}
            />
          </div>

          <div className="login-field">
            <label htmlFor="password" className="login-label">
              Mật khẩu
            </label>
            <input
              id="password"
              type="password"
              className="admin-input"
              placeholder="••••••••"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={loading}
            />
          </div>

          {error && (
            <p className="login-error" role="alert">
              {error}
            </p>
          )}

          <button
            type="submit"
            className="admin-btn login-submit"
            disabled={loading}
          >
            {loading ? "Đang đăng nhập..." : "Đăng nhập"}
          </button>
        </form>
      </div>

      <style>{`
        .login-page {
          display: flex;
          align-items: center;
          justify-content: center;
          min-height: 100dvh;
          padding: 24px;
          background: var(--admin-bg);
        }

        .login-card {
          width: 100%;
          max-width: 420px;
          padding: 40px 36px;
        }

        .login-brand {
          display: flex;
          align-items: center;
          gap: 10px;
          margin-bottom: 32px;
          font-weight: 900;
          font-size: 18px;
          text-transform: uppercase;
          letter-spacing: 0;
          color: var(--admin-text);
        }

        .login-logo {
          display: grid;
          width: 40px;
          height: 40px;
          place-items: center;
          border-radius: var(--admin-radius-md);
          background: var(--admin-accent);
          color: #ffffff;
          flex: 0 0 40px;
        }

        .login-heading {
          margin: 0 0 6px;
          font-family: var(--font-display), Arial, sans-serif;
          font-size: 28px;
          line-height: 1.1;
          text-transform: uppercase;
          color: var(--admin-text);
        }

        .login-sub {
          margin: 0 0 28px;
          color: var(--admin-muted);
          font-size: 14px;
        }

        .login-form {
          display: grid;
          gap: 18px;
        }

        .login-field {
          display: grid;
          gap: 6px;
        }

        .login-label {
          font-size: 13px;
          font-weight: 750;
          color: var(--admin-text);
          text-transform: uppercase;
          letter-spacing: 0.02em;
        }

        .login-error {
          margin: 0;
          padding: 10px 14px;
          border-radius: var(--admin-radius-sm);
          background: rgba(220, 38, 38, 0.1);
          color: var(--admin-danger);
          font-size: 14px;
          font-weight: 700;
        }

        .login-submit {
          width: 100%;
          margin-top: 4px;
          min-height: 46px;
          font-size: 15px;
        }

        .login-submit:disabled {
          opacity: 0.65;
          cursor: not-allowed;
        }
      `}</style>
    </div>
  );
}
