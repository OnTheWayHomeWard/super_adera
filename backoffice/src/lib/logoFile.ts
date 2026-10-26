/** What the server accepts for an app logo (LogoImageProcessor): decoded by the JDK, so no WebP. */
export const LOGO_TYPES = ["image/png", "image/jpeg", "image/gif", "image/bmp"] as const;
export const LOGO_ACCEPT = LOGO_TYPES.join(",");
export const LOGO_FORMATS_TEXT = "PNG, JPEG, GIF or BMP";
export const MAX_LOGO_BYTES = 2 * 1024 * 1024;

/** A message when the file cannot be a logo, otherwise null (the server still checks the real content). */
export function logoFileProblem(file: { size: number; type: string }): string | null {
  if (file.size > MAX_LOGO_BYTES) return "The logo is larger than 2 MiB.";
  if (!(LOGO_TYPES as readonly string[]).includes(file.type)) return `Use a ${LOGO_FORMATS_TEXT} image.`;
  return null;
}
