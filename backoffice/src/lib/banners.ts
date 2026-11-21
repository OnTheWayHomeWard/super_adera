/**
 * Banners (registry-service /admin/api/v1/banners): shapes, labels, the form <-> API mapping and the client-side checks
 * that mirror the server's rules (the server still decides; these only catch mistakes before saving).
 */
import type { I18n } from "./types";

export type Placement = "HOME_HERO" | "FEATURED" | "PROMOTIONS" | "CATALOG_TOP";
export type BannerCategory = "PROMOTION" | "FEATURED_APP" | "ANNOUNCEMENT" | "EVENT";
export type ActionType = "MINI_APP" | "INTERNAL" | "EXTERNAL" | "NONE";
export type BannerStatus = "DRAFT" | "PUBLISHED" | "PAUSED";
/** Computed by the server from the status and the clock; never stored. */
export type BannerState = "DRAFT" | "PAUSED" | "SCHEDULED" | "LIVE" | "ENDED";
export type ImageSize = "small" | "medium" | "large";

export const PLACEMENTS: Placement[] = ["HOME_HERO", "FEATURED", "PROMOTIONS", "CATALOG_TOP"];
export const CATEGORIES: BannerCategory[] = ["PROMOTION", "FEATURED_APP", "ANNOUNCEMENT", "EVENT"];
export const ACTION_TYPES: ActionType[] = ["MINI_APP", "INTERNAL", "EXTERNAL", "NONE"];

export const PLACEMENT_LABEL: Record<Placement, string> = {
  HOME_HERO: "Home hero (top of the home screen)",
  FEATURED: "Featured strip (home screen)",
  PROMOTIONS: "Promotions screen",
  CATALOG_TOP: "Top of the mini-app catalog",
};
export const CATEGORY_LABEL: Record<BannerCategory, string> = {
  PROMOTION: "Promotion",
  FEATURED_APP: "Featured app",
  ANNOUNCEMENT: "Announcement",
  EVENT: "Event",
};
export const ACTION_LABEL: Record<ActionType, string> = {
  MINI_APP: "Open a mini app",
  INTERNAL: "Open a super-app screen",
  EXTERNAL: "Open a web page",
  NONE: "Nothing (image only)",
};

export interface BannerImage {
  url: string;
  width: number;
  height: number;
}

export interface BannerApp {
  id: string;
  slug: string;
  name: I18n;
  logoUrl: string | null;
  status: string;
}

export interface BannerView {
  id: string;
  title: I18n;
  subtitle: I18n;
  placement: Placement;
  category: BannerCategory;
  appId: string | null;
  app: BannerApp | null;
  actionType: ActionType;
  target: string | null;
  imageId: string | null;
  images: Partial<Record<ImageSize, BannerImage>> | null;
  startsAt: string;
  endsAt: string | null;
  priority: number;
  status: BannerStatus;
  state: BannerState;
  /** LIVE, with an image, and the linked app (if any) LIVE: what users get right now. */
  visible: boolean;
  createdBy: string | null;
  updatedBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BannerSettings {
  externalDomains: string[];
  imageSizes: Record<ImageSize, number>;
  maxImageBytes: number;
}

// ------------------------------------------------------------------------------------------------ state

export const STATE_LABEL: Record<BannerState, string> = {
  DRAFT: "Draft",
  PAUSED: "Paused",
  SCHEDULED: "Scheduled",
  LIVE: "Live",
  ENDED: "Ended",
};
export const STATE_COLOR: Record<BannerState, string> = {
  DRAFT: "default",
  PAUSED: "orange",
  SCHEDULED: "blue",
  LIVE: "green",
  ENDED: "default",
};
export const STATE_HELP: Record<BannerState, string> = {
  DRAFT: "Not shown. Publish it when it is ready.",
  PAUSED: "Not shown. Publish it again to bring it back.",
  SCHEDULED: "Published: it appears by itself at the start time.",
  LIVE: "Published and inside its time window: users see it.",
  ENDED: "Published, but the end time has passed: not shown. Move the end time to bring it back.",
};

/** The list tabs, in order. "ALL" sends no state filter. */
export const STATE_TABS: { key: BannerState | "ALL"; label: string }[] = [
  { key: "LIVE", label: "Live" },
  { key: "SCHEDULED", label: "Scheduled" },
  { key: "DRAFT", label: "Draft" },
  { key: "PAUSED", label: "Paused" },
  { key: "ENDED", label: "Ended" },
  { key: "ALL", label: "All" },
];

export function stateFilter(tab: string | null | undefined): BannerState | undefined {
  return tab && tab !== "ALL" && tab in STATE_LABEL ? (tab as BannerState) : undefined;
}

/** Why a banner whose state is LIVE is still not shown, or null when nothing is wrong. */
export function hiddenReason(b: Pick<BannerView, "state" | "visible" | "app" | "imageId">): string | null {
  if (b.state !== "LIVE" || b.visible) return null;
  if (!b.imageId) return "No image: not shown.";
  if (b.app && b.app.status !== "LIVE") return `Not shown: the linked app is ${b.app.status}. It comes back when the app is LIVE.`;
  return "Not shown.";
}

/** Which status buttons make sense now. Publishing also needs an image. */
export function statusActions(b: Pick<BannerView, "status" | "imageId">): { publish: boolean; pause: boolean; draft: boolean; publishBlocked: string | null } {
  return {
    publish: b.status !== "PUBLISHED",
    pause: b.status === "PUBLISHED",
    draft: b.status !== "DRAFT",
    publishBlocked: b.status !== "PUBLISHED" && !b.imageId ? "Upload an image first." : null,
  };
}

// ------------------------------------------------------------------------------------------------ targets

export const TARGET_HELP: Record<ActionType, string> = {
  MINI_APP: "A page inside the mini app, starting with / (like /promo/first-ride). Leave empty for the app's home.",
  INTERNAL: "A super-app screen link like adera://wallet.",
  EXTERNAL: "An https:// address on an allowed domain.",
  NONE: "No target: tapping the banner does nothing.",
};

const URL_CHARS = "[A-Za-z0-9._~!$&'()*+,;=:@%/?#\\[\\]-]";
const ROUTE = new RegExp(`^/(?!/)${URL_CHARS}*$`);
const INTERNAL = new RegExp(`^adera://[A-Za-z0-9]${URL_CHARS}*$`);

export function hostAllowed(host: string, domains: string[]): boolean {
  const h = host.toLowerCase().replace(/\.$/, "");
  return domains.some((d) => h === d || h.endsWith(`.${d}`));
}

/** The server's target rules, so the form can say what is wrong before saving. null = fine. */
export function targetProblem(actionType: ActionType, target: string | null | undefined, domains: string[]): string | null {
  const t = (target ?? "").trim();
  switch (actionType) {
    case "MINI_APP":
      return !t || ROUTE.test(t) ? null : "Must start with / (a page inside the app), or be empty for the app's home.";
    case "INTERNAL":
      if (!t) return "Required: a link like adera://wallet.";
      return INTERNAL.test(t) ? null : "Must be a super-app link like adera://wallet.";
    case "EXTERNAL": {
      if (!t) return "Required: an https:// address.";
      let url: URL;
      try {
        url = new URL(t);
      } catch {
        return "Must be an https:// address.";
      }
      if (url.protocol !== "https:" || !/^https:\/\//i.test(t) || /\s/.test(t)) return "Must be an https:// address.";
      if (url.username || url.password) return "Must not contain a user name or password.";
      if (!domains.length) return "External links are switched off on this deployment.";
      if (!hostAllowed(url.hostname, domains)) return `The domain ${url.hostname} is not allowed. Allowed: ${domains.join(", ")} (and their subdomains).`;
      return null;
    }
    case "NONE":
      return t ? "Must be empty when the banner does nothing." : null;
  }
}

/** Whether the form must ask for a linked app. */
export function needsApp(category: BannerCategory | undefined, actionType: ActionType | undefined): boolean {
  return category === "FEATURED_APP" || actionType === "MINI_APP";
}

// ------------------------------------------------------------------------------------------------ form

export interface BannerFormValues {
  titleEn: string;
  titleAm: string;
  titleOm: string;
  subEn: string;
  subAm: string;
  subOm: string;
  placement: Placement;
  category: BannerCategory;
  appId: string | null;
  actionType: ActionType;
  target: string;
  /** ISO-8601 */
  startsAt: string;
  /** ISO-8601 or null = open-ended */
  endsAt: string | null;
  priority: number;
}

export function emptyBannerForm(now: Date = new Date()): BannerFormValues {
  return {
    titleEn: "",
    titleAm: "",
    titleOm: "",
    subEn: "",
    subAm: "",
    subOm: "",
    placement: "HOME_HERO",
    category: "PROMOTION",
    appId: null,
    actionType: "NONE",
    target: "",
    startsAt: new Date(Math.floor(now.getTime() / 60000) * 60000).toISOString(), // this minute: publishing shows it at once
    endsAt: null,
    priority: 0,
  };
}

export function toBannerForm(b: BannerView): BannerFormValues {
  return {
    titleEn: b.title.en ?? "",
    titleAm: b.title.am ?? "",
    titleOm: b.title.om ?? "",
    subEn: b.subtitle.en ?? "",
    subAm: b.subtitle.am ?? "",
    subOm: b.subtitle.om ?? "",
    placement: b.placement,
    category: b.category,
    appId: b.appId,
    actionType: b.actionType,
    target: b.target ?? "",
    startsAt: b.startsAt,
    endsAt: b.endsAt,
    priority: b.priority,
  };
}

function i18n(en: string, am: string, om: string): I18n {
  const out: I18n = {};
  if (en.trim()) out.en = en.trim();
  if (am.trim()) out.am = am.trim();
  if (om.trim()) out.om = om.trim();
  return out;
}

const sameInstant = (a: string | null, b: string | null) => (a && b ? new Date(a).getTime() === new Date(b).getTime() : a === b);

/**
 * Body for POST /banners (everything) or PATCH /banners/{id} (only what changed, so the audit log names real changes).
 * A target that makes no sense for the action type is sent as null (for example after switching to NONE).
 */
export function buildBannerPayload(values: BannerFormValues, original?: BannerView): Record<string, unknown> {
  const target = values.actionType === "NONE" ? null : values.target.trim() || null;
  const all: Record<string, unknown> = {
    title: i18n(values.titleEn, values.titleAm, values.titleOm),
    subtitle: i18n(values.subEn, values.subAm, values.subOm),
    placement: values.placement,
    category: values.category,
    appId: values.appId || null,
    actionType: values.actionType,
    target,
    startsAt: values.startsAt,
    endsAt: values.endsAt || null,
    priority: values.priority,
  };
  if (!original) {
    const create = { ...all };
    for (const key of ["appId", "target", "endsAt"]) if (create[key] === null) delete create[key];
    if (Object.keys(create.subtitle as I18n).length === 0) delete create.subtitle;
    return create;
  }
  const before = buildBannerPayload(toBannerForm(original));
  const patch: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(all)) {
    const old = before[key] ?? (key === "subtitle" ? {} : null);
    if (key === "startsAt" || key === "endsAt") {
      if (!sameInstant(value as string | null, old as string | null)) patch[key] = value;
    } else if (JSON.stringify(value) !== JSON.stringify(old)) {
      patch[key] = value;
    }
  }
  return patch;
}

/** Server field name -> form input, so a validation message lands next to the right box. */
export const SERVER_TO_FORM: Record<string, string> = { title: "titleEn", subtitle: "subEn" };

// ------------------------------------------------------------------------------------------------ image

/** What the server decodes (the logo's rules): no WebP or SVG. */
export const BANNER_IMAGE_TYPES = ["image/png", "image/jpeg", "image/gif", "image/bmp"] as const;
export const BANNER_IMAGE_ACCEPT = BANNER_IMAGE_TYPES.join(",");
export const DEFAULT_MAX_IMAGE_BYTES = 4 * 1024 * 1024;

export function bannerFileProblem(file: { size: number; type: string }, maxBytes = DEFAULT_MAX_IMAGE_BYTES): string | null {
  if (file.size > maxBytes) return `The image is larger than ${(maxBytes / 1024 / 1024).toFixed(maxBytes % (1024 * 1024) ? 1 : 0)} MiB.`;
  if (!(BANNER_IMAGE_TYPES as readonly string[]).includes(file.type)) return "Use a PNG, JPEG, GIF or BMP image.";
  return null;
}

/** The smallest stored size that covers `cssWidth` CSS pixels at this pixel ratio (what the super app should do too). */
export function pickImage(images: BannerView["images"], cssWidth: number, pixelRatio = 1): BannerImage | null {
  if (!images) return null;
  const sizes = (["small", "medium", "large"] as ImageSize[]).map((s) => images[s]).filter((i): i is BannerImage => !!i);
  if (!sizes.length) return null;
  const need = cssWidth * pixelRatio;
  return sizes.find((i) => i.width >= need) ?? sizes[sizes.length - 1];
}

// ------------------------------------------------------------------------------------------------ preview

/** How each placement is laid out in the super app (the guide describes the same), for the preview. */
export const PREVIEW: Record<Placement, { widthPct: number; aspect: number; textOver: boolean; note: string }> = {
  HOME_HERO: { widthPct: 100, aspect: 16 / 7, textOver: true, note: "Full width carousel at the top of the home screen; text over the picture." },
  FEATURED: { widthPct: 70, aspect: 16 / 9, textOver: false, note: "Cards in a horizontal strip; title under the picture, with the linked app." },
  PROMOTIONS: { widthPct: 100, aspect: 2, textOver: false, note: "Full width cards in a list, title and subtitle under the picture." },
  CATALOG_TOP: { widthPct: 100, aspect: 4, textOver: true, note: "One slim banner above the mini-app catalog." },
};
