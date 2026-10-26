import { beforeEach, describe, expect, it } from "vitest";
import { clearSession, getSession, getToken, resetSessionCacheForTests, saveSession } from "./session";

describe("session storage", () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    resetSessionCacheForTests();
  });

  it("keeps the token in memory and sessionStorage, never localStorage", () => {
    saveSession({ token: "tok", aal: "aal2", email: "a@b.co" });
    expect(getToken()).toBe("tok");
    expect(sessionStorage.getItem("adera.backoffice.session")).toContain("tok");
    expect(localStorage.length).toBe(0);
  });

  it("survives a reload (memory gone, sessionStorage kept)", () => {
    saveSession({ token: "tok", aal: "aal1" });
    resetSessionCacheForTests();
    expect(getSession()).toEqual({ token: "tok", aal: "aal1", email: undefined });
  });

  it("clear removes both copies", () => {
    saveSession({ token: "tok", aal: "aal2" });
    clearSession();
    resetSessionCacheForTests();
    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem("adera.backoffice.session")).toBeNull();
  });

  it("ignores a damaged stored value", () => {
    sessionStorage.setItem("adera.backoffice.session", "{not json");
    expect(getSession()).toBeNull();
    sessionStorage.setItem("adera.backoffice.session", JSON.stringify({ token: 5, aal: "aal9" }));
    resetSessionCacheForTests();
    expect(getSession()).toBeNull();
  });
});
