import { describe, expect, it } from "vitest";
import { MAX_REASON, moderationBody } from "./moderation";

describe("moderation payloads", () => {
  it("sends only the status when no reason was typed", () => {
    expect(moderationBody("HIDDEN", "")).toEqual({ status: "HIDDEN" });
    expect(moderationBody("OPEN", "   ")).toEqual({ status: "OPEN" });
  });
  it("sends the trimmed reason", () => {
    expect(moderationBody("HIDDEN", "  spam link ")).toEqual({ status: "HIDDEN", reason: "spam link" });
  });
  it("never sends more than the server accepts", () => {
    expect((moderationBody("HIDDEN", "x".repeat(900)).reason ?? "").length).toBe(MAX_REASON);
  });
});
