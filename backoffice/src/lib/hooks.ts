import { useMutation, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { apiRequest, type Query, type RequestOptions } from "./client";
import type { Environment, SystemInfo } from "./types";

/** Reads one admin API path. The key should name what is read so later writes can refresh it. */
export function useApiQuery<T>(key: QueryKey, path: string, query?: Query, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ["bo", ...key],
    queryFn: async ({ signal }) => (await apiRequest<T>(path, { query, signal })).data,
    enabled: options.enabled ?? true,
  });
}

/** Same as useApiQuery but also gives the X-Total-Count header. */
export function useApiList<T>(key: QueryKey, path: string, query?: Query, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ["bo", ...key],
    queryFn: async ({ signal }) => {
      const r = await apiRequest<T[]>(path, { query, signal });
      return { rows: r.data ?? [], total: r.total };
    },
    enabled: options.enabled ?? true,
  });
}

/** A write. `invalidate` lists key prefixes (after "bo") to refetch once it succeeds. */
export function useApiMutation<TResult = unknown, TVars = void>(
  build: (vars: TVars) => { path: string } & RequestOptions,
  invalidate: QueryKey[] = [],
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (vars: TVars) => {
      const { path, ...options } = build(vars);
      return (await apiRequest<TResult>(path, options)).data;
    },
    onSuccess: async () => {
      await Promise.all(invalidate.map((key) => client.invalidateQueries({ queryKey: ["bo", ...key] })));
    },
  });
}

export function useRefreshKeys() {
  const client = useQueryClient();
  return (...keys: QueryKey[]) => Promise.all(keys.map((key) => client.invalidateQueries({ queryKey: ["bo", ...key] })));
}

/** Which environment this deployment is (GET system). undefined while loading or if it cannot be read. */
export function useDeploymentEnvironment(): Environment | undefined {
  const q = useApiQuery<SystemInfo>(["system"], "system");
  return q.data?.environment;
}
