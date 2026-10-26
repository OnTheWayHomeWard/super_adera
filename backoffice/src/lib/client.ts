import { ApiError, toApiError } from "./errors";
import { clearSession, getToken } from "./session";

/** Everything is same-origin: the gateway serves this dashboard and the API (dev: the Vite proxy does the same). */
export const ADMIN_BASE = "/admin/api/v1";

export type Query = Record<string, string | number | boolean | null | undefined>;

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  query?: Query;
  body?: unknown;
  form?: FormData;
  signal?: AbortSignal;
  /** Skip the global "go to login" reaction (used by the login screens themselves). */
  quiet?: boolean;
}

export interface ApiResponse<T> {
  data: T;
  total?: number;
}

/** What the app shell does when the server says the session is no good. */
export type AuthEvent = { type: "unauthorized" } | { type: "mfa_required" } | { type: "not_staff" };
type Listener = (event: AuthEvent) => void;
const listeners = new Set<Listener>();

export function onAuthEvent(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function emit(event: AuthEvent): void {
  listeners.forEach((l) => l(event));
}

export function buildUrl(path: string, query?: Query): string {
  const url = path.startsWith("http") || path.startsWith("/") ? path : `${ADMIN_BASE}/${path}`;
  if (!query) return url;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") {
      params.set(key, String(value));
    }
  }
  const text = params.toString();
  return text ? `${url}${url.includes("?") ? "&" : "?"}${text}` : url;
}

/** Tells the app shell about 401 / mfa_required / not_staff answers. Exported for tests. */
export function reactToAuthFailure(error: ApiError): void {
  if (error.status === 401) {
    clearSession();
    emit({ type: "unauthorized" });
  } else if (error.status === 403 && error.code === "mfa_required") {
    emit({ type: "mfa_required" });
  } else if (error.status === 403 && error.code === "not_staff") {
    clearSession();
    emit({ type: "not_staff" });
  }
}

export async function apiRequest<T = unknown>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = { Accept: "application/json" };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let body: BodyInit | undefined;
  if (options.form) {
    body = options.form;
  } else if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.body);
  }
  let response: Response;
  try {
    response = await fetch(buildUrl(path, options.query), { method: options.method ?? "GET", headers, body, signal: options.signal });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    throw new ApiError(0, "network_error");
  }
  const text = await response.text();
  if (!response.ok) {
    const error = toApiError(response.status, text);
    if (!options.quiet) reactToAuthFailure(error);
    throw error;
  }
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      throw new ApiError(response.status, "server_error");
    }
  }
  const totalHeader = response.headers.get("X-Total-Count");
  const total = totalHeader !== null && totalHeader !== "" && !Number.isNaN(Number(totalHeader)) ? Number(totalHeader) : undefined;
  return { data: data as T, total };
}

/** Shorthand used all over the screens: the parsed body only. */
export async function api<T = unknown>(path: string, options: RequestOptions = {}): Promise<T> {
  return (await apiRequest<T>(path, options)).data;
}
