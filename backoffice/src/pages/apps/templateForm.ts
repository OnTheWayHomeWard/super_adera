import dayjs, { type Dayjs } from "dayjs";
import { LANGS, type Lang, type Template, type TemplateParam } from "../../lib/types";

export const TEXT_KEYS = ["title", "body", "sms", "emailSubject", "emailHtml"] as const;
export type TextKey = (typeof TEXT_KEYS)[number];

export interface TemplateFormValues {
  code: string;
  kind: "DIRECT" | "IN_APP";
  category: "TRANSACTIONAL" | "REMINDER" | "PROMOTIONAL";
  params: TemplateParam[];
  content: Partial<Record<Lang, Partial<Record<TextKey, string>>>>;
  active: boolean;
  validFrom: Dayjs | null;
  expiresAt: Dayjs | null;
}

export const emptyTemplateForm: TemplateFormValues = {
  code: "",
  kind: "DIRECT",
  category: "TRANSACTIONAL",
  params: [],
  content: { en: {} },
  active: true,
  validFrom: null,
  expiresAt: null,
};

export function toTemplateForm(t: Template): TemplateFormValues {
  return {
    code: t.code,
    kind: t.kind,
    category: t.category,
    params: t.params.map((p) => ({ ...p })),
    content: JSON.parse(JSON.stringify(t.content)),
    active: t.active,
    validFrom: t.validFrom ? dayjs(t.validFrom) : null,
    expiresAt: t.expiresAt ? dayjs(t.expiresAt) : null,
  };
}

/** Drops blank texts and empty languages: the server ignores blanks anyway, and this makes "changed?" comparisons honest. */
export function cleanContent(content: TemplateFormValues["content"] | undefined): Template["content"] {
  const out: Template["content"] = {};
  for (const lang of LANGS) {
    const texts = content?.[lang];
    if (!texts) continue;
    const kept: Partial<Record<TextKey, string>> = {};
    for (const key of TEXT_KEYS) {
      const v = texts[key];
      if (typeof v === "string" && v.trim() !== "") kept[key] = v;
    }
    if (Object.keys(kept).length) out[lang] = kept;
  }
  return out;
}

export function cleanParams(params: TemplateFormValues["params"] | undefined): TemplateParam[] {
  return (params ?? []).map((p) => ({
    name: String(p.name ?? "").trim(),
    maxLength: Number(p.maxLength ?? 100),
    required: p.required !== false,
    allowLinks: p.allowLinks === true,
  }));
}

const iso = (d: Dayjs | null | undefined) => (d ? d.toDate().toISOString() : null);
const sameInstant = (a: string | null, b: string | null) => (a === null || b === null ? a === b : new Date(a).getTime() === new Date(b).getTime());

/**
 * POST body (create) or PATCH body (only what changed). Sending params or content makes the server bump the template
 * version, so unchanged ones are left out on edit.
 */
export function buildTemplatePayload(values: TemplateFormValues, appId: string, original?: Template): Record<string, unknown> {
  const params = cleanParams(values.params);
  const content = cleanContent(values.content);
  const validFrom = iso(values.validFrom);
  const expiresAt = iso(values.expiresAt);
  if (!original) {
    const body: Record<string, unknown> = { appId, code: values.code.trim(), kind: values.kind, category: values.category, params, content, active: values.active };
    if (validFrom) body.validFrom = validFrom;
    if (expiresAt) body.expiresAt = expiresAt;
    return body;
  }
  const patch: Record<string, unknown> = {};
  if (values.category !== original.category) patch.category = values.category;
  if (JSON.stringify(params) !== JSON.stringify(cleanParams(original.params))) patch.params = params;
  if (JSON.stringify(content) !== JSON.stringify(cleanContent(original.content))) patch.content = content;
  if (values.active !== original.active) patch.active = values.active;
  if (!sameInstant(validFrom, original.validFrom)) patch.validFrom = validFrom;
  if (!sameInstant(expiresAt, original.expiresAt)) patch.expiresAt = expiresAt;
  return patch;
}

/** Placeholders like {name} used in the texts that are not declared as params (the server refuses these). */
export function undeclaredPlaceholders(values: Pick<TemplateFormValues, "params" | "content">): string[] {
  const declared = new Set((values.params ?? []).map((p) => String(p?.name ?? "").trim()));
  const missing = new Set<string>();
  for (const lang of LANGS) {
    for (const key of TEXT_KEYS) {
      const text = values.content?.[lang]?.[key];
      if (!text) continue;
      for (const m of text.matchAll(/\{([a-zA-Z][a-zA-Z0-9_]*)}/g)) {
        if (!declared.has(m[1])) missing.add(m[1]);
      }
    }
  }
  return [...missing];
}

/** Shown above the edit form: templates are rendered when a message is delivered, not when it was accepted. */
export const TEMPLATE_EDIT_NOTICE = "Changes also apply to messages already queued for delivery: the text is filled in when a message is sent, using the template as it is then.";
