/**
 * The staff session token. Kept in memory and in sessionStorage (cleared when the tab closes), never in localStorage.
 * "aal1" = only the emailed code was entered; "aal2" = the authenticator-app step is done too (the admin API needs it).
 */
export interface StoredSession {
  token: string;
  aal: "aal1" | "aal2";
  email?: string;
}

const KEY = "adera.backoffice.session";
let memory: StoredSession | null = null;
let loaded = false;

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    return null;
  }
}

export function getSession(): StoredSession | null {
  if (!loaded) {
    loaded = true;
    try {
      const raw = storage()?.getItem(KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<StoredSession>;
        if (typeof parsed.token === "string" && (parsed.aal === "aal1" || parsed.aal === "aal2")) {
          memory = { token: parsed.token, aal: parsed.aal, email: parsed.email };
        }
      }
    } catch {
      memory = null;
    }
  }
  return memory;
}

export function saveSession(session: StoredSession): void {
  memory = session;
  loaded = true;
  try {
    storage()?.setItem(KEY, JSON.stringify(session));
  } catch {
    // storage blocked: the in-memory copy still works until the tab is reloaded
  }
}

export function clearSession(): void {
  memory = null;
  loaded = true;
  try {
    storage()?.removeItem(KEY);
  } catch {
    // ignore
  }
}

export function getToken(): string | null {
  return getSession()?.token ?? null;
}

/** For tests: forget the in-memory copy so the next read goes to storage again. */
export function resetSessionCacheForTests(): void {
  memory = null;
  loaded = false;
}
