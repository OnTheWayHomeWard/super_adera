import { describe, expect, it } from "vitest";
import { checkCodeAllowance, refusedText, sentText, waitText } from "./loginCodes";

const reply = (status: number, body: unknown) => async () => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

describe("login code pre-flight", () => {
  it("allowed: 200 with the remaining allowance", async () => {
    const a = await checkCodeAllowance("a@b.co", reply(200, { allowed: true, limitPerHour: 10, limitPerDay: 20, remainingThisHour: 9, remainingToday: 19 }));
    expect(a).toMatchObject({ allowed: true, limitPerHour: 10, remainingThisHour: 9 });
  });
  it("refused: 429 with the time to wait", async () => {
    const a = await checkCodeAllowance("a@b.co", reply(429, { error: "too_many_codes", allowed: false, retryAfterSeconds: 1500, limitPerHour: 10, limitPerDay: 20, remainingThisHour: 0, remainingToday: 12 }));
    expect(a?.allowed).toBe(false);
    expect(refusedText(a!)).toMatch(/at most 10 per hour and 20 per day/);
    expect(refusedText(a!)).toMatch(/25 minutes/);
  });
  it("an unanswerable check never blocks sign-in", async () => {
    expect(await checkCodeAllowance("a@b.co", async () => { throw new Error("offline"); })).toBeNull();
    expect(await checkCodeAllowance("a@b.co", reply(503, { error: "x" }))).toBeNull();
    expect(await checkCodeAllowance("a@b.co", reply(429, "<html>gateway</html>"))).toBeNull();
    expect(await checkCodeAllowance("a@b.co", reply(400, { error: "validation_failed" }))).toBeNull();
  });
  it("waits are readable", () => {
    expect(waitText(45)).toBe("45 seconds");
    expect(waitText(600)).toBe("10 minutes");
    expect(waitText(3900)).toBe("1 hour 5 minutes");
    expect(waitText(7200)).toBe("2 hours");
  });
  it("the sent message does not claim a delivery and states the limit", () => {
    const t = sentText("a@b.co", { allowed: true, limitPerHour: 10, limitPerDay: 20, remainingThisHour: 7, remainingToday: 17, retryAfterSeconds: 0 });
    expect(t).toMatch(/If a@b.co is a staff address/);
    expect(t).toMatch(/within a minute/);
    expect(t).toMatch(/at most 10 codes per hour \(7 left this hour\)/);
    expect(sentText("a@b.co", null)).not.toMatch(/left this hour/);
  });
});
