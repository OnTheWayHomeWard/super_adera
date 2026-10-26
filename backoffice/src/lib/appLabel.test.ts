import { describe, expect, it } from "vitest";
import { appLabel } from "./format";

describe("appLabel", () => {
  const id = "0f4a620e-2f2b-4401-bbec-b14026641e19";
  it("shows name and slug", () => {
    expect(appLabel({ name: { en: "Adera Fix", am: "አደራ" }, slug: "adera-fix" }, id)).toBe("Adera Fix (adera-fix)");
  });
  it("shows just one when the other is missing or the same", () => {
    expect(appLabel({ name: {}, slug: "adera-fix" }, id)).toBe("adera-fix");
    expect(appLabel({ name: { en: "adera-fix" }, slug: "adera-fix" }, id)).toBe("adera-fix");
    expect(appLabel({ name: { en: "Adera Fix" } }, id)).toBe("Adera Fix");
  });
  it("falls back to the id while the apps are loading or for an app that is gone", () => {
    expect(appLabel(undefined, id)).toBe(id);
  });
});
