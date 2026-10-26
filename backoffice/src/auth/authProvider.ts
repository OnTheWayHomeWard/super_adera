import type { AuthProvider } from "@refinedev/core";
import { api } from "../lib/client";
import { ApiError, describeError } from "../lib/errors";
import { getIdentity, setIdentity } from "../lib/identity";
import { kratos } from "../lib/kratos";
import { clearSession, getSession, getToken, saveSession } from "../lib/session";
import { setLoginNotice } from "./notice";

interface MeResponse {
  id: string;
  role: string;
}

/** Asks the admin API who this session is (it also proves the account is active staff and at aal2). */
async function loadIdentity(email?: string): Promise<void> {
  const me = await api<MeResponse>("me", { quiet: true });
  setIdentity({ id: me.id, role: me.role, email: email ?? getSession()?.email });
}

export const authProvider: AuthProvider = {
  /** Called once the authenticator step is done: `{ token, email }` is the aal2 session token. */
  login: async ({ token, email }: { token: string; email?: string }) => {
    saveSession({ token, aal: "aal2", email });
    try {
      await loadIdentity(email);
      return { success: true, redirectTo: "/" };
    } catch (error) {
      const code = error instanceof ApiError ? error.code : "";
      if (code === "mfa_required") {
        saveSession({ token, aal: "aal1", email });
      } else {
        clearSession();
      }
      setIdentity(null);
      return { success: false, error: { name: code || "login_failed", message: describeError(error) } };
    }
  },

  logout: async () => {
    const token = getToken();
    clearSession();
    setIdentity(null);
    if (token) {
      await kratos.logout(token);
    }
    return { success: true, redirectTo: "/login" };
  },

  check: async () => {
    const session = getSession();
    if (!session || session.aal !== "aal2") {
      return { authenticated: false, redirectTo: "/login" };
    }
    if (!getIdentity()) {
      try {
        await loadIdentity();
      } catch (error) {
        if (error instanceof ApiError && error.code === "mfa_required") {
          saveSession({ ...session, aal: "aal1" });
        } else if (error instanceof ApiError && error.code === "not_staff") {
          clearSession();
          setLoginNotice({ kind: "error", text: describeError(error) });
        } else if (error instanceof ApiError && error.status === 401) {
          clearSession();
          setLoginNotice({ kind: "info", text: "Your session ended. Sign in again." });
        } else {
          // the server is unreachable or failing: do not sign the person out, show the problem on the page
          return { authenticated: true };
        }
        return { authenticated: false, redirectTo: "/login" };
      }
    }
    return { authenticated: true };
  },

  onError: async (error) => {
    const status = (error as { statusCode?: number })?.statusCode;
    if (status === 401) {
      return { logout: true, redirectTo: "/login", error: error as Error };
    }
    return { error: error as Error };
  },

  getIdentity: async () => getIdentity(),

  getPermissions: async () => getIdentity()?.role ?? null,
};
