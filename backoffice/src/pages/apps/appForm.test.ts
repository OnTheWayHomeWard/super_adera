import { describe, expect, it } from "vitest";
import type { AppView } from "../../lib/types";
import { buildAppPayload, emptyAppForm, ExtraJsonError, parseExtra, toFormValues } from "./appForm";

const app: AppView = {
  id: "1",
  slug: "adera-fix",
  name: { en: "Fix", am: "ጥገና" },
  description: { en: "Repairs" },
  logoImageId: null,
  logoUrl: null,
  brandColor: "#0F9D58",
  categories: ["home"],
  markets: ["ET"],
  publisher: { name: "Adera", legalName: null, supportEmail: "help@adera.et", supportPhone: null, website: null },
  entryUrl: "https://fix.adera.et",
  allowedOrigins: ["https://fix.adera.et"],
  status: "DRAFT",
  priority: 50,
  ratingAvg: 0,
  ratingCount: 0,
  extra: { a: 1, b: "x" },
  createdAt: "2026-10-25T00:00:00Z",
  updatedAt: "2026-10-25T00:00:00Z",
};

describe("buildAppPayload", () => {
  it("create sends the slug and leaves empty optional fields out", () => {
    const body = buildAppPayload({ ...emptyAppForm, slug: " my-app ", nameEn: "My app", extraJson: "{}" });
    expect(body.slug).toBe("my-app");
    expect(body.name).toEqual({ en: "My app" });
    expect(body).not.toHaveProperty("supportEmail");
    expect(body).not.toHaveProperty("extra");
    expect(body.markets).toEqual(["ET"]);
  });

  it("edit sends nothing when nothing changed", () => {
    expect(buildAppPayload(toFormValues(app), app)).toEqual({});
  });

  it("edit sends only changed fields, and null to clear an optional field", () => {
    const values = { ...toFormValues(app), priority: 10, supportEmail: "", nameAm: "" };
    expect(buildAppPayload(values, app)).toEqual({
      priority: 10,
      supportEmail: null,
      name: { en: "Fix" },
    });
  });

  it("removing a key from extra is sent as null because the server merges", () => {
    const values = { ...toFormValues(app), extraJson: JSON.stringify({ a: 2, c: true }) };
    expect(buildAppPayload(values, app)).toEqual({ extra: { a: 2, c: true, b: null } });
  });
});

describe("parseExtra", () => {
  it("accepts objects and rejects everything else", () => {
    expect(parseExtra("")).toEqual({});
    expect(parseExtra('{"x":1}')).toEqual({ x: 1 });
    expect(() => parseExtra("[1]")).toThrow(ExtraJsonError);
    expect(() => parseExtra("{oops")).toThrow(ExtraJsonError);
  });
});
