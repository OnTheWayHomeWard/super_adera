import { describe, expect, it } from "vitest";
import { countsFor, describeCounts, type ConsentCounts } from "./consentCounts";

const data: ConsentCounts = {
  appId: "a",
  slug: "demo-ride",
  capabilities: [
    { capability: "location.fine", label: "Your precise location", granted: 12, denied: 3, blocked: 1 },
    { capability: "notify.sms", label: "Text messages (SMS)", granted: 0, denied: 0, blocked: 0 },
  ],
};

describe("consent counts on the app page", () => {
  it("finds the row of a capability, or nothing when staff never granted it", () => {
    expect(countsFor(data, "location.fine")?.granted).toBe(12);
    expect(countsFor(data, "pay.request")).toBeUndefined();
    expect(countsFor(undefined, "location.fine")).toBeUndefined();
  });
  it("describes the three numbers in words, never as a list of people", () => {
    expect(describeCounts(data.capabilities[0])).toBe("12 granted, 3 denied, 1 blocked");
  });
  it("says plainly when nobody has answered yet", () => {
    expect(describeCounts(data.capabilities[1])).toBe("nobody has answered yet");
  });
  it("shows a dash for a capability that is not granted to the app", () => {
    expect(describeCounts(undefined)).toBe("-");
  });
  it("uses singular wording for one blocked user", () => {
    expect(describeCounts({ capability: "x", label: "x", granted: 1, denied: 0, blocked: 1 })).toBe("1 granted, 0 denied, 1 blocked");
  });
});
