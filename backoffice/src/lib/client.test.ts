import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, apiRequest, buildUrl, onAuthEvent, type AuthEvent } from "./client";
import { ApiError } from "./errors";
import { getSession, resetSessionCacheForTests, saveSession } from "./session";

function respond(status: number, body: string, headers: Record<string, string> = {}) {
  return vi.fn().mockResolvedValue(new Response(body, { status, headers }));
}

describe("api client", () => {
  const events: AuthEvent[] = [];
  let off: () => void;

  beforeEach(() => {
    sessionStorage.clear();
    resetSessionCacheForTests();
    events.length = 0;
    off = onAuthEvent((e) => events.push(e));
  });
  afterEach(() => {
    off();
    vi.unstubAllGlobals();
  });

  it("sends the session token as a Bearer header", async () => {
    saveSession({ token: "secret-token", aal: "aal2" });
    const fetchMock = respond(200, '{"id":"1"}');
    vi.stubGlobal("fetch", fetchMock);
    await api("me");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/admin/api/v1/me");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer secret-token");
  });

  it("builds queries and drops empty values", () => {
    expect(buildUrl("audit", { limit: 50, actor: "", target: undefined, action: "app." })).toBe("/admin/api/v1/audit?limit=50&action=app.");
    expect(buildUrl("/backoffice/api/health/x")).toBe("/backoffice/api/health/x");
  });

  it("reads the X-Total-Count header", async () => {
    vi.stubGlobal("fetch", respond(200, "[1,2]", { "X-Total-Count": "42" }));
    expect(await apiRequest("audit")).toEqual({ data: [1, 2], total: 42 });
  });

  it("401 clears the session and tells the shell to go to the login page", async () => {
    saveSession({ token: "t", aal: "aal2" });
    vi.stubGlobal("fetch", respond(401, ""));
    await expect(api("me")).rejects.toMatchObject({ status: 401, code: "unauthorized" });
    expect(events).toEqual([{ type: "unauthorized" }]);
    expect(getSession()).toBeNull();
  });

  it("403 mfa_required goes to the authenticator step and keeps the token", async () => {
    saveSession({ token: "t", aal: "aal1" });
    vi.stubGlobal("fetch", respond(403, '{"error":"mfa_required"}'));
    await expect(api("me")).rejects.toMatchObject({ code: "mfa_required" });
    expect(events).toEqual([{ type: "mfa_required" }]);
    expect(getSession()?.token).toBe("t");
  });

  it("403 not_staff shows a clear message and signs out", async () => {
    saveSession({ token: "t", aal: "aal2" });
    vi.stubGlobal("fetch", respond(403, '{"error":"not_staff"}'));
    await expect(api("me")).rejects.toMatchObject({ code: "not_staff" });
    expect(events).toEqual([{ type: "not_staff" }]);
    expect(getSession()).toBeNull();
  });

  it("403 insufficient_role is an ordinary error (no redirect)", async () => {
    vi.stubGlobal("fetch", respond(403, '{"error":"insufficient_role"}'));
    await expect(api("apps", { method: "POST", body: {} })).rejects.toMatchObject({ code: "insufficient_role" });
    expect(events).toEqual([]);
  });

  it("quiet requests do not trigger redirects", async () => {
    vi.stubGlobal("fetch", respond(401, ""));
    await expect(api("me", { quiet: true })).rejects.toBeInstanceOf(ApiError);
    expect(events).toEqual([]);
  });

  it("maps a network failure", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("failed to fetch")));
    await expect(api("me")).rejects.toMatchObject({ code: "network_error", status: 0 });
  });

  it("sends JSON bodies and multipart forms without forcing a content type", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await api("apps", { method: "POST", body: { a: 1 } });
    expect((fetchMock.mock.calls[0][1].headers as Record<string, string>)["Content-Type"]).toBe("application/json");
    const form = new FormData();
    form.append("file", new Blob(["x"]), "x.png");
    await api("apps/1/logo", { method: "POST", form });
    expect((fetchMock.mock.calls[1][1].headers as Record<string, string>)["Content-Type"]).toBeUndefined();
  });
});
