import { useSyncExternalStore } from "react";
import { isRole, type Role } from "./roles";

/** Who is signed in (id, role, email), kept so any screen can read the role synchronously. */
export interface Identity {
  id: string;
  role: Role;
  email?: string;
}

let current: Identity | null = null;
const subscribers = new Set<() => void>();

export function getIdentity(): Identity | null {
  return current;
}

export function setIdentity(value: { id: string; role: unknown; email?: string } | null): void {
  current = value && isRole(value.role) ? { id: value.id, role: value.role, email: value.email } : null;
  subscribers.forEach((s) => s());
}

function subscribe(listener: () => void): () => void {
  subscribers.add(listener);
  return () => subscribers.delete(listener);
}

export function useStaff(): Identity | null {
  return useSyncExternalStore(subscribe, getIdentity, getIdentity);
}

export function useRole(): Role | null {
  return useStaff()?.role ?? null;
}
