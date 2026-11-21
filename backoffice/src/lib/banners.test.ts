import { describe, expect, it } from "vitest";
import {
  BANNER_IMAGE_ACCEPT,
  bannerFileProblem,
  type BannerView,
  buildBannerPayload,
  emptyBannerForm,
  hiddenReason,
  needsApp,
  pickImage,
  STATE_TABS,
  stateFilter,
  statusActions,
  targetProblem,
  toBannerForm,
} from "./banners";

const banner: BannerView = {
  id: "b1",
  title: { en: "First ride free", am: "ነፃ ጉዞ" },
  subtitle: {},
  placement: "HOME_HERO",
  category: "FEATURED_APP",
  appId: "a1",
  app: { id: "a1", slug: "demo-ride", name: { en: "Ride Hailing" }, logoUrl: null, status: "LIVE" },
  actionType: "MINI_APP",
  target: "/promo/first-ride",
  imageId: "i1",
  images: {
    small: { url: "s", width: 480, height: 210 },
    medium: { url: "m", width: 960, height: 420 },
    large: { url: "l", width: 1600, height: 700 },
  },
  startsAt: "2026-11-20T00:00:00Z",
  endsAt: null,
  priority: 100,
  status: "PUBLISHED",
  state: "LIVE",
  visible: true,
  createdBy: null,
  updatedBy: null,
  createdAt: "2026-11-20T00:00:00Z",
  updatedAt: "2026-11-20T00:00:00Z",
};

const DOMAINS = ["adera.et", "example.com"];

describe("buildBannerPayload", () => {
  it("create sends every field and leaves out empty optional ones", () => {
    const body = buildBannerPayload({ ...emptyBannerForm(new Date("2026-12-01T10:00:30Z")), titleEn: " Hello ", target: "  " });
    expect(body).toEqual({
      title: { en: "Hello" },
      placement: "HOME_HERO",
      category: "PROMOTION",
      actionType: "NONE",
      startsAt: "2026-12-01T10:00:00.000Z",
      priority: 0,
    });
  });

  it("edit sends nothing when nothing changed, even if the time is written differently", () => {
    expect(buildBannerPayload(toBannerForm(banner), banner)).toEqual({});
    expect(buildBannerPayload({ ...toBannerForm(banner), startsAt: "2026-11-20T03:00:00+03:00" }, banner)).toEqual({});
  });

  it("edit sends only changed fields, and null to clear", () => {
    const values = { ...toBannerForm(banner), priority: 5, titleAm: "", subEn: "Up to 150 birr", endsAt: "2026-12-31T00:00:00.000Z" };
    expect(buildBannerPayload(values, banner)).toEqual({
      priority: 5,
      title: { en: "First ride free" },
      subtitle: { en: "Up to 150 birr" },
      endsAt: "2026-12-31T00:00:00.000Z",
    });
    expect(buildBannerPayload({ ...toBannerForm(banner), target: "" }, banner)).toEqual({ target: null });
  });

  it("switching to NONE also clears the target, as the server requires", () => {
    const values = { ...toBannerForm(banner), actionType: "NONE" as const, category: "PROMOTION" as const, appId: null };
    expect(buildBannerPayload(values, banner)).toEqual({ actionType: "NONE", target: null, category: "PROMOTION", appId: null });
  });
});

describe("targetProblem mirrors the server's rules", () => {
  it("mini app routes", () => {
    for (const ok of ["", "/", "/promo/first-ride", "/p?ref=banner#top"]) expect(targetProblem("MINI_APP", ok, DOMAINS)).toBeNull();
    for (const bad of ["promo", "//evil.test", "https://evil.test", "/a b"]) expect(targetProblem("MINI_APP", bad, DOMAINS)).not.toBeNull();
  });
  it("super-app links", () => {
    expect(targetProblem("INTERNAL", "adera://wallet", DOMAINS)).toBeNull();
    for (const bad of ["", "adera://", "wallet", "https://adera.et/wallet"]) expect(targetProblem("INTERNAL", bad, DOMAINS)).not.toBeNull();
  });
  it("external links: https on an allowed domain or a subdomain of one", () => {
    expect(targetProblem("EXTERNAL", "https://news.adera.et/x", DOMAINS)).toBeNull();
    expect(targetProblem("EXTERNAL", "https://example.com", DOMAINS)).toBeNull();
    expect(targetProblem("EXTERNAL", "https://evil.test/", DOMAINS)).toMatch(/evil\.test is not allowed/);
    expect(targetProblem("EXTERNAL", "https://notadera.et/", DOMAINS)).toMatch(/not allowed/);
    expect(targetProblem("EXTERNAL", "http://adera.et/", DOMAINS)).toMatch(/https/);
    expect(targetProblem("EXTERNAL", "https://u:p@adera.et/", DOMAINS)).toMatch(/user name/);
    expect(targetProblem("EXTERNAL", "https://adera.et/", [])).toMatch(/switched off/);
  });
  it("NONE takes no target", () => {
    expect(targetProblem("NONE", "", DOMAINS)).toBeNull();
    expect(targetProblem("NONE", "/x", DOMAINS)).not.toBeNull();
  });
  it("an app is needed for a featured app or a mini-app action", () => {
    expect(needsApp("FEATURED_APP", "NONE")).toBe(true);
    expect(needsApp("PROMOTION", "MINI_APP")).toBe(true);
    expect(needsApp("EVENT", "EXTERNAL")).toBe(false);
  });
});

describe("state tabs and status buttons", () => {
  it("tabs are Live, Scheduled, Draft, Paused, Ended, All; All sends no filter", () => {
    expect(STATE_TABS.map((t) => t.label)).toEqual(["Live", "Scheduled", "Draft", "Paused", "Ended", "All"]);
    expect(stateFilter("ALL")).toBeUndefined();
    expect(stateFilter("ENDED")).toBe("ENDED");
    expect(stateFilter("nonsense")).toBeUndefined();
  });
  it("publishing needs an image; pause only when published", () => {
    expect(statusActions({ status: "DRAFT", imageId: null })).toEqual({ publish: true, pause: false, draft: false, publishBlocked: "Upload an image first." });
    expect(statusActions({ status: "PUBLISHED", imageId: "i" })).toEqual({ publish: false, pause: true, draft: true, publishBlocked: null });
    expect(statusActions({ status: "PAUSED", imageId: "i" })).toEqual({ publish: true, pause: false, draft: true, publishBlocked: null });
  });
  it("says why a LIVE banner is not shown", () => {
    expect(hiddenReason(banner)).toBeNull();
    expect(hiddenReason({ ...banner, visible: false, app: { ...banner.app!, status: "PAUSED" } })).toMatch(/linked app is PAUSED/);
    expect(hiddenReason({ ...banner, state: "SCHEDULED", visible: false })).toBeNull();
  });
});

describe("images", () => {
  it("accepts what the server decodes, up to the limit", () => {
    expect(bannerFileProblem({ size: 1000, type: "image/jpeg" })).toBeNull();
    expect(bannerFileProblem({ size: 1000, type: "image/webp" })).toMatch(/PNG, JPEG, GIF or BMP/);
    expect(bannerFileProblem({ size: 4 * 1024 * 1024 + 1, type: "image/png" })).toMatch(/larger than 4 MiB/);
    expect(BANNER_IMAGE_ACCEPT).not.toContain("webp");
  });
  it("picks the smallest size that covers the space", () => {
    expect(pickImage(banner.images, 393, 3)?.url).toBe("l");
    expect(pickImage(banner.images, 275, 3)?.url).toBe("m");
    expect(pickImage(banner.images, 120, 2)?.url).toBe("s");
    expect(pickImage(banner.images, 2000, 2)?.url).toBe("l");
    expect(pickImage(null, 100)).toBeNull();
  });
});
