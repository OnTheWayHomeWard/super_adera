import dayjs from "dayjs";
import { describe, expect, it } from "vitest";
import type { Template } from "../../lib/types";
import { buildTemplatePayload, cleanContent, emptyTemplateForm, TEMPLATE_EDIT_NOTICE, toTemplateForm, undeclaredPlaceholders } from "./templateForm";

const t: Template = {
  id: "t1",
  appId: "a1",
  code: "job-accepted",
  kind: "DIRECT",
  category: "TRANSACTIONAL",
  params: [{ name: "name", maxLength: 50, required: true, allowLinks: false }],
  content: { en: { body: "Hi {name}" } },
  active: true,
  validFrom: null,
  expiresAt: null,
  version: 1,
  usableNow: true,
  unusableReason: null,
  createdAt: "2026-10-25T00:00:00Z",
  updatedAt: "2026-10-25T00:00:00Z",
};

describe("template payloads", () => {
  it("create sends everything the server requires", () => {
    const body = buildTemplatePayload({ ...emptyTemplateForm, code: " job-accepted ", content: { en: { body: "Hi", title: "  ", sms: "" } } }, "a1");
    expect(body).toMatchObject({ appId: "a1", code: "job-accepted", kind: "DIRECT", category: "TRANSACTIONAL", params: [], content: { en: { body: "Hi" } }, active: true });
    expect(body).not.toHaveProperty("expiresAt");
  });

  it("edit with no change sends an empty patch (so the version is not bumped)", () => {
    expect(buildTemplatePayload(toTemplateForm(t), "a1", t)).toEqual({});
  });

  it("edit sends only what changed", () => {
    const v = { ...toTemplateForm(t), active: false, expiresAt: dayjs("2027-01-01T00:00:00Z") };
    expect(buildTemplatePayload(v, "a1", t)).toEqual({ active: false, expiresAt: "2027-01-01T00:00:00.000Z" });
    const v2 = toTemplateForm(t);
    v2.content = { en: { body: "Hello {name}" } };
    expect(buildTemplatePayload(v2, "a1", t)).toEqual({ content: { en: { body: "Hello {name}" } } });
  });

  it("clearing the expiry sends null", () => {
    const withExpiry = { ...t, expiresAt: "2027-01-01T00:00:00Z" };
    expect(buildTemplatePayload({ ...toTemplateForm(withExpiry), expiresAt: null }, "a1", withExpiry)).toEqual({ expiresAt: null });
  });
});

describe("helpers", () => {
  it("cleanContent drops blanks and empty languages", () => {
    expect(cleanContent({ en: { body: "x", title: " " }, am: { body: "" } })).toEqual({ en: { body: "x" } });
  });
  it("finds placeholders that are not declared", () => {
    expect(undeclaredPlaceholders({ params: [{ name: "a", maxLength: 1, required: true, allowLinks: false }], content: { en: { body: "{a} {b}", sms: "{c}" } } }).sort()).toEqual(["b", "c"]);
  });
});

describe("template edit notice", () => {
  it("says that queued messages are affected too", () => {
    expect(TEMPLATE_EDIT_NOTICE).toMatch(/already queued/);
    expect(TEMPLATE_EDIT_NOTICE).toMatch(/when a message is sent/);
  });
});
