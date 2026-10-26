import type { BaseRecord, CrudFilter, DataProvider } from "@refinedev/core";
import { ADMIN_BASE, apiRequest, type Query } from "./lib/client";

/**
 * Maps Refine resources onto the admin API. List filters use the query parameter name as the filter field, so a screen
 * that filters `status` with `eq` simply sends `?status=...`.
 */
function filterQuery(filters: CrudFilter[] | undefined): Query {
  const query: Query = {};
  for (const f of filters ?? []) {
    if ("field" in f && f.value !== undefined && f.value !== null && f.value !== "") {
      query[f.field] = Array.isArray(f.value) ? f.value.join(",") : (f.value as string | number | boolean);
    }
  }
  return query;
}

interface ListSpec {
  path: string;
  /** "page" = 0-based page/size, "offset" = limit/offset, "none" = the whole list in one answer. */
  paging: "page" | "offset" | "none";
  /** The answer is `{ items, total }` instead of a plain array. */
  wrapped?: boolean;
}

const LISTS: Record<string, ListSpec> = {
  apps: { path: "apps", paging: "none" },
  users: { path: "users", paging: "page", wrapped: true },
  staff: { path: "staff", paging: "none" },
  audit: { path: "audit", paging: "offset" },
  notifications: { path: "notification/notifications", paging: "offset" },
  reviews: { path: "reviews", paging: "page" },
  reports: { path: "reports", paging: "page" },
  templates: { path: "notification/templates", paging: "none" },
};

const ONE: Record<string, string> = {
  apps: "apps",
  users: "users",
  templates: "notification/templates",
  notifications: "notification/notifications",
};

function unsupported(what: string): never {
  throw new Error(`${what} is not available for this resource`);
}

export const dataProvider: DataProvider = {
  getApiUrl: () => ADMIN_BASE,

  getList: async <T extends BaseRecord = BaseRecord>({ resource, pagination, filters, meta }: Parameters<DataProvider["getList"]>[0]) => {
    const spec = LISTS[resource] ?? unsupported(`Listing ${resource}`);
    const size = pagination?.pageSize ?? 25;
    const page = pagination?.currentPage ?? 1;
    const query: Query = { ...filterQuery(filters), ...(meta?.query as Query | undefined) };
    if (spec.paging === "page") {
      query.page = page - 1;
      query.size = size;
    } else if (spec.paging === "offset") {
      query.limit = size;
      query.offset = (page - 1) * size;
    }
    const response = await apiRequest<unknown>(spec.path, { query });
    const rows = (spec.wrapped ? (response.data as { items: T[] }).items : (response.data as T[])) ?? [];
    const total = spec.wrapped ? (response.data as { total: number }).total : (response.total ?? rows.length);
    return { data: rows, total };
  },

  getOne: async <T extends BaseRecord = BaseRecord>({ resource, id }: Parameters<DataProvider["getOne"]>[0]) => {
    const base = ONE[resource] ?? unsupported(`Reading one ${resource}`);
    const response = await apiRequest<T>(`${base}/${encodeURIComponent(String(id))}`);
    return { data: response.data };
  },

  create: async <T extends BaseRecord = BaseRecord, V = object>({ resource, variables }: Parameters<DataProvider["create"]>[0] & { variables: V }) => {
    const path = LISTS[resource]?.path ?? unsupported(`Creating ${resource}`);
    const response = await apiRequest<T>(path, { method: "POST", body: variables });
    return { data: response.data };
  },

  update: async <T extends BaseRecord = BaseRecord, V = object>({ resource, id, variables }: Parameters<DataProvider["update"]>[0] & { variables: V }) => {
    const base = ONE[resource] ?? (resource === "staff" ? "staff" : unsupported(`Updating ${resource}`));
    const response = await apiRequest<T>(`${base}/${encodeURIComponent(String(id))}`, { method: "PATCH", body: variables });
    return { data: response.data };
  },

  deleteOne: async () => unsupported("Deleting"),

  custom: async ({ url, method, payload, query }) => {
    const response = await apiRequest(url, { method: method.toUpperCase() as "GET", body: payload, query: query as Query | undefined });
    return { data: response.data as never };
  },
};
