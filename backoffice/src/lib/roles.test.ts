import { describe, expect, it } from "vitest";
import { atLeast, can, isRole } from "./roles";

describe("role gating", () => {
  it("VIEWER is read-only", () => {
    expect(can("VIEWER", "write")).toBe(false);
    expect(can("VIEWER", "audit.read")).toBe(false);
    expect(can("VIEWER", "user.pii")).toBe(false);
    expect(can("VIEWER", "staff.manage")).toBe(false);
  });
  it("ADMIN writes and reads audit and personal data, but does not manage staff or revive killed apps", () => {
    expect(can("ADMIN", "write")).toBe(true);
    expect(can("ADMIN", "audit.read")).toBe(true);
    expect(can("ADMIN", "user.pii")).toBe(true);
    expect(can("ADMIN", "staff.manage")).toBe(false);
    expect(can("ADMIN", "app.revive")).toBe(false);
  });
  it("SUPER_ADMIN can do everything", () => {
    for (const a of ["write", "audit.read", "user.pii", "staff.manage", "app.revive"] as const) expect(can("SUPER_ADMIN", a)).toBe(true);
  });
  it("no role means no abilities", () => {
    expect(can(null, "write")).toBe(false);
    expect(atLeast(undefined, "VIEWER")).toBe(false);
  });
  it("only accepts the three known roles", () => {
    expect(isRole("ADMIN")).toBe(true);
    expect(isRole("admin")).toBe(false);
    expect(isRole("ROOT")).toBe(false);
  });
});
