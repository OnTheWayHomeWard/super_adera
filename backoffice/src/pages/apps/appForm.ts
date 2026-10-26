import { splitList } from "../../lib/format";
import type { AppView, I18n } from "../../lib/types";

export interface AppFormValues {
  slug: string;
  nameEn: string;
  nameAm: string;
  nameOm: string;
  descEn: string;
  descAm: string;
  descOm: string;
  brandColor: string;
  categories: string[];
  markets: string[];
  publisherName: string;
  publisherLegalName: string;
  supportEmail: string;
  supportPhone: string;
  website: string;
  entryUrl: string;
  allowedOrigins: string[];
  priority: number;
  extraJson: string;
}

export const APP_FORM_FIELDS = [
  "slug",
  "name",
  "description",
  "brandColor",
  "categories",
  "markets",
  "publisherName",
  "publisherLegalName",
  "supportEmail",
  "supportPhone",
  "website",
  "entryUrl",
  "allowedOrigins",
  "priority",
  "extra",
] as const;

/** Server field name -> the form input that carries it, so a message lands next to the right box. */
export const SERVER_TO_FORM: Record<string, string> = {
  name: "nameEn",
  description: "descEn",
  extra: "extraJson",
};

export const emptyAppForm: AppFormValues = {
  slug: "",
  nameEn: "",
  nameAm: "",
  nameOm: "",
  descEn: "",
  descAm: "",
  descOm: "",
  brandColor: "",
  categories: [],
  markets: ["ET"],
  publisherName: "",
  publisherLegalName: "",
  supportEmail: "",
  supportPhone: "",
  website: "",
  entryUrl: "",
  allowedOrigins: [],
  priority: 100,
  extraJson: "{}",
};

export function toFormValues(app: AppView): AppFormValues {
  return {
    slug: app.slug,
    nameEn: app.name.en ?? "",
    nameAm: app.name.am ?? "",
    nameOm: app.name.om ?? "",
    descEn: app.description.en ?? "",
    descAm: app.description.am ?? "",
    descOm: app.description.om ?? "",
    brandColor: app.brandColor ?? "",
    categories: app.categories,
    markets: app.markets,
    publisherName: app.publisher.name ?? "",
    publisherLegalName: app.publisher.legalName ?? "",
    supportEmail: app.publisher.supportEmail ?? "",
    supportPhone: app.publisher.supportPhone ?? "",
    website: app.publisher.website ?? "",
    entryUrl: app.entryUrl ?? "",
    allowedOrigins: app.allowedOrigins,
    priority: app.priority,
    extraJson: JSON.stringify(app.extra ?? {}, null, 2),
  };
}

function i18n(en: string, am: string, om: string): I18n {
  const out: I18n = {};
  if (en.trim()) out.en = en.trim();
  if (am.trim()) out.am = am.trim();
  if (om.trim()) out.om = om.trim();
  return out;
}

/** Optional text field: empty clears it (null), otherwise the trimmed text. */
const textOrNull = (v: string | undefined) => (v && v.trim() ? v.trim() : null);

export class ExtraJsonError extends Error {}

export function parseExtra(text: string): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = text.trim() ? JSON.parse(text) : {};
  } catch {
    throw new ExtraJsonError("not valid JSON");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new ExtraJsonError("must be a JSON object like {\"key\": \"value\"}");
  }
  return parsed as Record<string, unknown>;
}

/**
 * Body for POST /apps (all fields) or PATCH /apps/{id} (only what changed, so the audit log stays meaningful).
 * `extra` is merged by the server: a key you want gone must be sent as null.
 */
export function buildAppPayload(values: AppFormValues, original?: AppView): Record<string, unknown> {
  const extra = parseExtra(values.extraJson);
  const all: Record<string, unknown> = {
    name: i18n(values.nameEn, values.nameAm, values.nameOm),
    description: i18n(values.descEn, values.descAm, values.descOm),
    brandColor: textOrNull(values.brandColor),
    categories: values.categories,
    markets: values.markets,
    publisherName: textOrNull(values.publisherName),
    publisherLegalName: textOrNull(values.publisherLegalName),
    supportEmail: textOrNull(values.supportEmail),
    supportPhone: textOrNull(values.supportPhone),
    website: textOrNull(values.website),
    entryUrl: textOrNull(values.entryUrl),
    allowedOrigins: values.allowedOrigins,
    priority: values.priority,
    extra,
  };
  if (!original) {
    const create: Record<string, unknown> = { slug: values.slug.trim(), ...all };
    // optional empties are simply left out on create
    for (const key of ["brandColor", "publisherName", "publisherLegalName", "supportEmail", "supportPhone", "website", "entryUrl"]) {
      if (create[key] === null) delete create[key];
    }
    if (Object.keys(extra).length === 0) delete create.extra;
    return create;
  }
  const before = toFormValues(original);
  const beforeAll = buildAppPayload({ ...before, slug: original.slug });
  const patch: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(all)) {
    if (key === "extra") continue;
    if (JSON.stringify(value) !== JSON.stringify(beforeAll[key] ?? null)) patch[key] = value;
  }
  const oldExtra = original.extra ?? {};
  const extraPatch: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(extra)) {
    if (JSON.stringify(oldExtra[k]) !== JSON.stringify(v)) extraPatch[k] = v;
  }
  for (const k of Object.keys(oldExtra)) {
    if (!(k in extra)) extraPatch[k] = null;
  }
  if (Object.keys(extraPatch).length) patch.extra = extraPatch;
  return patch;
}

export { splitList };
