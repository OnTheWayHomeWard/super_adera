import { describe, expect, it } from "vitest";
import { summariseAudit } from "./auditSummary";

describe("audit details", () => {
  it("shows limits in words, null as unlimited", () => {
    expect(summariseAudit("limit.set", { channel: "SMS", maxPerUserPerDay: 3, maxPerDay: 100, maxDistinctUsersPerDay: null, maxTotal: 1000 })).toBe(
      "SMS: per user per day 3, per day 100, distinct users per day unlimited, total 1000",
    );
  });
  it("shows payment settings in words", () => {
    expect(summariseAudit("payment.set", { currencies: ["ETB", "USD"], allowHold: true, minAmountMinor: 100, maxAmountMinor: null })).toBe(
      "currencies ETB, USD, hold allowed, amount 100 to no maximum",
    );
  });
  it("falls back to key=value and shortens long text", () => {
    expect(summariseAudit("app.status", { from: "DRAFT", to: "LIVE" })).toBe("from=DRAFT to=LIVE");
    expect(summariseAudit("x", { a: "y".repeat(200) }).endsWith("...")).toBe(true);
  });
});
