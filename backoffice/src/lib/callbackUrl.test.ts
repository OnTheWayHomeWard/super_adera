import { describe, expect, it } from "vitest";
import { callbackUrlBody, callbackUrlProblem } from "./callbackUrl";

describe("consent callback URL of an app environment", () => {
  it("accepts an https URL and an empty value (no callbacks)", () => {
    expect(callbackUrlProblem("https://partner.example.com/adera/events", "PROD")).toBeNull();
    expect(callbackUrlProblem("  ", "PROD")).toBeNull();
    expect(callbackUrlProblem("", "DEV")).toBeNull();
  });
  it("refuses credentials, other schemes and a missing host", () => {
    expect(callbackUrlProblem("https://user:pw@partner.example.com/e", "PROD")).toMatch(/user name/);
    expect(callbackUrlProblem("ftp://partner.example.com/e", "PROD")).toMatch(/https/);
    expect(callbackUrlProblem("https://", "PROD")).toMatch(/host/);
    expect(callbackUrlProblem("not a url", "PROD")).toMatch(/https/);
    expect(callbackUrlProblem("https://a.example.com/" + "x".repeat(300), "PROD")).toMatch(/300/);
  });
  it("accepts plain http only for DEV (a partner's local test server)", () => {
    expect(callbackUrlProblem("http://localhost:9000/hook", "DEV")).toBeNull();
    expect(callbackUrlProblem("http://localhost:9000/hook", "PROD")).toMatch(/https/);
  });
  it("sends only the URL, trimmed, and null to remove it, so saving it never touches the addresses", () => {
    expect(callbackUrlBody("  https://p.example.com/e ")).toEqual({ callbackUrl: "https://p.example.com/e" });
    expect(callbackUrlBody("   ")).toEqual({ callbackUrl: null });
  });
});
