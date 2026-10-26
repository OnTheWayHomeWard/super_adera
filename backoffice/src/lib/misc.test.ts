import { describe, expect, it } from "vitest";
import { limitText, splitList } from "./format";
import { explainNotification } from "./notificationHelp";
import { usagePercent } from "../pages/apps/tabs/UsageTab";
import { targetLink } from "../components/AuditTable";

describe("limit semantics", () => {
  it("null is unlimited and 0 is none", () => {
    expect(limitText(null)).toBe("unlimited");
    expect(limitText(0)).toMatch(/none/);
    expect(limitText(5)).toBe("5");
  });
  it("usage percent", () => {
    expect(usagePercent(5, null)).toBeNull();
    expect(usagePercent(5, 10)).toBe(50);
    expect(usagePercent(20, 10)).toBe(100);
    expect(usagePercent(0, 0)).toBe(0);
    expect(usagePercent(1, 0)).toBe(100);
  });
});

describe("splitList", () => {
  it("splits on commas and new lines", () => {
    expect(splitList("1.1.1.1, 2.2.2.2\n3.3.3.3\n\n")).toEqual(["1.1.1.1", "2.2.2.2", "3.3.3.3"]);
    expect(splitList(undefined)).toEqual([]);
  });
});

describe("why didn't it arrive", () => {
  it("explains a muted user", () => {
    const lines = explainNotification({ status: "SUPPRESSED", reason: "recipient_opted_out", attempts: 0, channel: "SMS" });
    expect(lines.join(" ")).toMatch(/muted/);
  });
  it("explains retries that ran out", () => {
    expect(explainNotification({ status: "FAILED", reason: "max_attempts:provider_down", attempts: 5, channel: "SMS" }).join(" ")).toMatch(/5 times/);
  });
});

describe("audit target links", () => {
  it("links apps and users only", () => {
    expect(targetLink({ targetType: "mini_app", targetId: "a1" })).toBe("/apps/a1");
    expect(targetLink({ targetType: "app_user", targetId: "u1" })).toBe("/users/u1");
    expect(targetLink({ targetType: "staff", targetId: "s1" })).toBeNull();
  });
});
