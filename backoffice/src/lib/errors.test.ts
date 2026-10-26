import { describe, expect, it } from "vitest";
import { ApiError, describeError, splitFieldErrors, toApiError } from "./errors";

describe("toApiError", () => {
  it("reads our error shape with fields", () => {
    const e = toApiError(400, JSON.stringify({ error: "validation_failed", fields: { name: "required", "content.en.body": "too long" } }));
    expect(e.status).toBe(400);
    expect(e.code).toBe("validation_failed");
    expect(e.fields).toEqual({ name: "required", "content.en.body": "too long" });
  });

  it("maps the gateway's HTML 429 page to rate_limited", () => {
    const e = toApiError(429, "<html><body>429 Too Many Requests</body></html>");
    expect(e.code).toBe("rate_limited");
    expect(describeError(e)).toMatch(/Too many requests/);
  });

  it("maps gateway 502/503/504 HTML to service_unavailable", () => {
    expect(toApiError(502, "<html>Bad gateway</html>").code).toBe("service_unavailable");
    expect(toApiError(503, "").code).toBe("service_unavailable");
  });

  it("maps an empty 401 to unauthorized and keeps 403 codes", () => {
    expect(toApiError(401, "").code).toBe("unauthorized");
    const e = toApiError(403, '{"error":"mfa_required"}');
    expect(e.status).toBe(403);
    expect(e.code).toBe("mfa_required");
  });

  it("explains not_ready_for_live with what is missing", () => {
    const e = toApiError(409, JSON.stringify({ error: "not_ready_for_live", fields: { missing: "logo,supportEmail" } }));
    expect(describeError(e)).toBe("Not ready for LIVE: missing a logo and a support email.");
  });

  it("falls back to the code for unknown errors and to a generic text for 5xx without JSON", () => {
    expect(describeError(new ApiError(418, "teapot"))).toBe("teapot");
    expect(toApiError(500, "boom").code).toBe("server_error");
  });

  it("exposes statusCode and errors the way Refine expects", () => {
    const e = new ApiError(400, "validation_failed", { a: "b" });
    expect(e.statusCode).toBe(400);
    expect(e.errors).toEqual({ a: "b" });
  });
});

describe("splitFieldErrors", () => {
  it("separates fields the form knows from the rest", () => {
    const e = new ApiError(400, "validation_failed", { slug: "bad", other: "x" });
    expect(splitFieldErrors(e, ["slug"])).toEqual({ byField: { slug: "bad" }, rest: ["other: x"] });
    expect(splitFieldErrors(new Error("x"), ["slug"])).toEqual({ byField: {}, rest: [] });
  });
});
