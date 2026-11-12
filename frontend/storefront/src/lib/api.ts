import { clearSession, getAccessToken, getRefreshToken, setSession } from "@/lib/session";
import { endpoints } from "@/lib/endpoints";

// Next.js inlines NEXT_PUBLIC_* at build time for both server and client code.
export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ?? "http://localhost:8085";

function getApiBase(): string {
  if (typeof window !== "undefined") return API_BASE;
  return process.env.SERVER_API_BASE_URL?.replace(/\/$/, "") || API_BASE;
}

export type ApiEnvelope<T> = {
  data: T;
  meta?: Record<string, unknown>;
  message?: string;
};

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public payload: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const READ_API_TIMEOUT_MS = 20_000;
const MUTATION_API_TIMEOUT_MS = 60_000;

function requestSignal(timeoutMs: number, signal?: AbortSignal | null): AbortSignal {
  const timeoutSignal = AbortSignal.timeout(timeoutMs);
  return signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal;
}

type QueryValue = string | number | boolean | null | undefined;

function buildUrl(
  path: string,
  query?: Record<string, QueryValue | QueryValue[]>,
): string {
  const base = getApiBase();
  const pathname = path.startsWith("/") ? path : `/${path}`;
  const url = new URL(`${base}${pathname}`);
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v == null || v === "") continue;
    if (Array.isArray(v)) {
      v.forEach((item) => item != null && url.searchParams.append(k, String(item)));
    } else {
      url.searchParams.set(k, String(v));
    }
  }
  return url.toString();
}

// A single in-flight refresh shared by all callers: if several requests 401 at
// once, exactly one hits /auth/refresh and the rest await its result.
let refreshInFlight: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const refreshToken = getRefreshToken();
      if (!refreshToken) return false;
      try {
        const res = await fetch(buildUrl(endpoints.auth.refresh), {
          method: "POST",
          credentials: "include",
          signal: requestSignal(READ_API_TIMEOUT_MS),
          headers: { Accept: "application/json", "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken }),
        });
        if (!res.ok) return false;
        const payload = await res.json().catch(() => null);
        const data = (payload?.data ?? payload) as { accessToken?: string; refreshToken?: string } | null;
        if (data?.accessToken && data?.refreshToken) {
          setSession({ accessToken: data.accessToken, refreshToken: data.refreshToken });
          return true;
        }
        return false;
      } catch {
        return false;
      }
    })();
    void refreshInFlight.finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

export async function apiFetch<T>(
  path: string,
  options?: RequestInit & {
    query?: Record<string, QueryValue | QueryValue[]>;
    next?: { revalidate?: number | false; tags?: string[] };
    _retry?: boolean;
  },
): Promise<{ data: T; meta?: Record<string, unknown> }> {
  const { query, headers: extraHeaders, next, _retry, ...rest } = options ?? {};
  const token = getAccessToken();

  const headers: Record<string, string> = {
    Accept: "application/json",
    ...(extraHeaders as Record<string, string> | undefined),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (rest.body && !(rest.body instanceof FormData) && !headers["Content-Type"]) {
    headers["Content-Type"] = "application/json";
  }

  const isGet = !rest.method || rest.method.toUpperCase() === "GET";
  // `cache: "no-store"` and `next.revalidate` are mutually exclusive in Next.js.
  // Only provide the default revalidation policy when the caller has not
  // explicitly selected a cache mode.
  const nextOptions = next ?? (isGet && !token && rest.cache == null ? { revalidate: 60 } : undefined);

  let res: Response;
  try {
    res = await fetch(buildUrl(path, query), {
      ...rest,
      signal: requestSignal(isGet ? READ_API_TIMEOUT_MS : MUTATION_API_TIMEOUT_MS, rest.signal),
      credentials: "include",
      headers,
      ...(nextOptions ? { next: nextOptions } : {}),
    });
  } catch (error) {
    if (error instanceof DOMException && (error.name === "TimeoutError" || error.name === "AbortError")) {
      throw new ApiError(408, "Máy chủ phản hồi quá chậm. Vui lòng thử lại.", null);
    }
    throw error;
  }

  // Silent recovery from an expired access token: refresh once, then replay the
  // original request. Guards against loops (_retry) and refreshing the refresh
  // call itself. A failed refresh clears the session (the user is truly logged out).
  if (res.status === 401 && !_retry && path !== endpoints.auth.refresh && getRefreshToken()) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      return apiFetch<T>(path, { ...options, _retry: true });
    }
    clearSession();
  }

  const payload = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, res.statusText, payload);

  const envelope = payload as ApiEnvelope<T>;
  return { data: envelope.data ?? (payload as T), meta: envelope.meta };
}
