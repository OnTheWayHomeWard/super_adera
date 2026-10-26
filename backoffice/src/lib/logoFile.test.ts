import { describe, expect, it } from "vitest";
import { LOGO_ACCEPT, logoFileProblem } from "./logoFile";

describe("logo file check", () => {
  it("accepts the formats the server decodes", () => {
    for (const type of ["image/png", "image/jpeg", "image/gif", "image/bmp"]) {
      expect(logoFileProblem({ size: 1000, type })).toBeNull();
    }
  });
  it("refuses WebP, SVG and anything else, naming the allowed formats", () => {
    for (const type of ["image/webp", "image/svg+xml", "application/pdf", ""]) {
      expect(logoFileProblem({ size: 1000, type })).toBe("Use a PNG, JPEG, GIF or BMP image.");
    }
  });
  it("refuses files over 2 MiB", () => {
    expect(logoFileProblem({ size: 2 * 1024 * 1024 + 1, type: "image/png" })).toMatch(/larger than 2 MiB/);
    expect(logoFileProblem({ size: 2 * 1024 * 1024, type: "image/png" })).toBeNull();
  });
  it("the file picker offers exactly those formats and no WebP", () => {
    expect(LOGO_ACCEPT).toBe("image/png,image/jpeg,image/gif,image/bmp");
    expect(LOGO_ACCEPT).not.toContain("webp");
  });
});
