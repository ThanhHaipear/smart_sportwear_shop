import { NextRequest, NextResponse } from "next/server";
import { ACCESS_TOKEN_COOKIE, USER_ROLE_COOKIE } from "@/modules/auth/session";

/**
 * A non-empty cookie is NOT enough: the access-token JWT expires in ~15 minutes
 * but its cookie is stored for 7 days, so a stale (expired) token would otherwise
 * sail past a "cookie present" check and land the user on the dashboard with a
 * dead session (API calls then 401 and pages render empty). We decode the JWT's
 * `exp` claim here and treat an expired/malformed token as logged-out so the user
 * is sent back to /login. No signature check is needed - this is only a UX gate;
 * the backend still verifies the token on every API call.
 */
function isSessionValid(token: string | undefined): boolean {
  if (!token || token.trim().length === 0) {
    return false;
  }
  const parts = token.split(".");
  if (parts.length < 2) {
    return false;
  }
  try {
    let base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    base64 += "=".repeat((4 - (base64.length % 4)) % 4);
    const payload = JSON.parse(atob(base64)) as { exp?: number };
    if (typeof payload.exp !== "number") {
      // No expiry claim - can't tell, so don't lock the user out over it.
      return true;
    }
    return payload.exp * 1000 > Date.now();
  } catch {
    return false;
  }
}

function getTokenRole(token: string | undefined): string | null {
  if (!token) return null;
  try {
    let base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    base64 += "=".repeat((4 - (base64.length % 4)) % 4);
    const payload = JSON.parse(atob(base64)) as { role?: unknown };
    return typeof payload.role === "string" ? payload.role : null;
  } catch {
    return null;
  }
}

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const token = request.cookies.get(ACCESS_TOKEN_COOKIE)?.value;
  const isLoggedIn = isSessionValid(token);
  const isLoginPage = pathname === "/login";
  const role = request.cookies.get(USER_ROLE_COOKIE)?.value ?? getTokenRole(token);

  if (!isLoggedIn && !isLoginPage) {
    const loginUrl = request.nextUrl.clone();
    loginUrl.pathname = "/login";
    const response = NextResponse.redirect(loginUrl);
    if (token) {
      // Clear the stale/expired cookie so /login doesn't bounce straight back
      // to the dashboard via the isLoggedIn check below.
      response.cookies.delete(ACCESS_TOKEN_COOKIE);
    }
    return response;
  }

  if (isLoggedIn && isLoginPage) {
    const dashboardUrl = request.nextUrl.clone();
    dashboardUrl.pathname = "/";
    return NextResponse.redirect(dashboardUrl);
  }

  if (isLoggedIn && pathname.startsWith("/inventory") && role === "SALES_STAFF") {
    const dashboardUrl = request.nextUrl.clone();
    dashboardUrl.pathname = "/";
    return NextResponse.redirect(dashboardUrl);
  }

  return NextResponse.next();
}

export const config = {
  // Authentication applies to application routes only. Public assets must stay
  // reachable without cookies because Next's image optimizer fetches local
  // images (for example /logo.png) from the server without the browser session.
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\..*).*)"]
};
