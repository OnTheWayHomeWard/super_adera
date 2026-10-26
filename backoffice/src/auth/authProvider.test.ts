import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getIdentity, setIdentity } from "../lib/identity";
import { getSession, resetSessionCacheForTests, saveSession } from "../lib/session";
import { authProvider } from "./authProvider";
import { consumeLoginNotice } from "./notice";

function respond(status: number, body: unknown) {
  return vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status }));
}

describe("authProvider", () => {
  beforeEach(() => {
    sessionStorage.clear();
    resetSessionCacheForTests();
    setIdentity(null);
    consumeLoginNotice();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("check: no session -> go to login", async () => {
    expect(await authProvider.check()).toEqual({ authenticated: false, redirectTo: "/login" });
  });

  it("check: an aal1 session must finish the authenticator step first", async () => {
    saveSession({ token: "t", aal: "aal1" });
    expect(await authProvider.check()).toMatchObject({ authenticated: false, redirectTo: "/login" });
  });

  it("check: loads the identity once from /me", async () => {
    saveSession({ token: "t", aal: "aal2", email: "boss@example.com" });
    const fetchMock = respond(200, { id: "u1", role: "ADMIN" });
    vi.stubGlobal("fetch", fetchMock);
    expect(await authProvider.check()).toEqual({ authenticated: true });
    expect(await authProvider.check()).toEqual({ authenticated: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(getIdentity()).toEqual({ id: "u1", role: "ADMIN", email: "boss@example.com" });
  });

  it("check: not_staff signs out and leaves a clear message", async () => {
    saveSession({ token: "t", aal: "aal2" });
    vi.stubGlobal("fetch", respond(403, { error: "not_staff" }));
    expect(await authProvider.check()).toMatchObject({ authenticated: false });
    expect(getSession()).toBeNull();
    expect(consumeLoginNotice()?.text).toMatch(/not an active staff member/);
  });

  it("check: mfa_required downgrades the session to aal1 so the login page asks for the authenticator code", async () => {
    saveSession({ token: "t", aal: "aal2" });
    vi.stubGlobal("fetch", respond(403, { error: "mfa_required" }));
    expect(await authProvider.check()).toMatchObject({ authenticated: false });
    expect(getSession()).toMatchObject({ token: "t", aal: "aal1" });
  });

  it("login: stores the aal2 token and the role", async () => {
    vi.stubGlobal("fetch", respond(200, { id: "u2", role: "VIEWER" }));
    const result = await authProvider.login({ token: "tok", email: "v@example.com" });
    expect(result.success).toBe(true);
    expect(getIdentity()?.role).toBe("VIEWER");
    expect(getSession()).toMatchObject({ token: "tok", aal: "aal2" });
  });

  it("login: a non-staff account is refused with a message and nothing is kept", async () => {
    vi.stubGlobal("fetch", respond(403, { error: "not_staff" }));
    const result = await authProvider.login({ token: "tok", email: "x@example.com" });
    expect(result.success).toBe(false);
    expect(result.error?.message).toMatch(/not an active staff member/);
    expect(getSession()).toBeNull();
    expect(getIdentity()).toBeNull();
  });

  it("logout clears the session and identity", async () => {
    saveSession({ token: "t", aal: "aal2" });
    setIdentity({ id: "u", role: "ADMIN" });
    vi.stubGlobal("fetch", respond(200, {}));
    const result = await authProvider.logout({});
    expect(result).toMatchObject({ success: true, redirectTo: "/login" });
    expect(getSession()).toBeNull();
    expect(getIdentity()).toBeNull();
  });

  it("onError: 401 logs out", async () => {
    expect(await authProvider.onError({ statusCode: 401 })).toMatchObject({ logout: true });
    expect(await authProvider.onError({ statusCode: 400 })).not.toHaveProperty("logout");
  });
});
