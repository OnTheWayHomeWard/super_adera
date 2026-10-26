import dayjs from "dayjs";

/** Local time, seconds precision: staff compare these with logs, so be exact rather than "3 hours ago". */
export function formatTime(value: string | null | undefined): string {
  if (!value) return "-";
  const d = dayjs(value);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : String(value);
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : "-";
}

/** Display name of an I18n object: English first, then any other language. */
export function i18nText(value: Partial<Record<string, string>> | null | undefined): string {
  if (!value) return "";
  return value.en ?? Object.values(value).find(Boolean) ?? "";
}

export function pluralise(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** null = unlimited, 0 = none (the notification limit semantics). */
export function limitText(value: number | null | undefined): string {
  if (value === null || value === undefined) return "unlimited";
  if (value === 0) return "0 (none allowed)";
  return String(value);
}

export function userName(u: { firstName: string | null; lastName: string | null }): string {
  return [u.firstName, u.lastName].filter(Boolean).join(" ");
}

export function download(filename: string, text: string, type = "application/x-pem-file"): void {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** "a, b ,c" -> ["a","b","c"] (empty entries dropped); also splits on new lines. */
export function splitList(text: string | undefined | null): string[] {
  return (text ?? "").split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
}
