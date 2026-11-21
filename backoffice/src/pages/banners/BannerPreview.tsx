import { Avatar, Typography } from "antd";
import { i18nText } from "../../lib/format";
import { CATEGORY_LABEL, PREVIEW, type BannerCategory, type Placement } from "../../lib/banners";
import type { I18n } from "../../lib/types";

interface Props {
  placement: Placement;
  category: BannerCategory;
  title: I18n;
  subtitle: I18n;
  imageUrl: string | null;
  app?: { name: I18n; slug: string; logoUrl: string | null } | null;
  lang?: "en" | "am" | "om";
}

const PHONE_WIDTH = 360;

/** Roughly how the banner looks in the super app for its placement, on a phone-width frame. */
export function BannerPreview({ placement, category, title, subtitle, imageUrl, app, lang = "en" }: Props) {
  const spec = PREVIEW[placement];
  const width = Math.round(((PHONE_WIDTH - 24) * spec.widthPct) / 100);
  const height = Math.round(width / spec.aspect);
  const t = title[lang] || i18nText(title) || "Title";
  const s = subtitle[lang] || i18nText(subtitle);
  const picture = (
    <div
      data-testid="banner-preview-picture"
      style={{
        width,
        height,
        borderRadius: 12,
        overflow: "hidden",
        position: "relative",
        background: imageUrl ? `center / cover no-repeat url("${imageUrl}")` : "repeating-linear-gradient(45deg, #ddd, #ddd 10px, #eee 10px, #eee 20px)",
      }}
    >
      {spec.textOver && (
        <div style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", justifyContent: "flex-end", padding: 12, background: "linear-gradient(transparent 40%, rgba(0,0,0,.65))", color: "#fff" }}>
          <strong style={{ fontSize: placement === "CATALOG_TOP" ? 13 : 16, lineHeight: 1.2 }}>{t}</strong>
          {s && placement !== "CATALOG_TOP" ? <span style={{ fontSize: 12, opacity: 0.9 }}>{s}</span> : null}
        </div>
      )}
      <span style={{ position: "absolute", top: 8, left: 8, fontSize: 10, background: "rgba(255,255,255,.9)", color: "#333", borderRadius: 4, padding: "1px 6px" }}>
        {CATEGORY_LABEL[category]}
      </span>
    </div>
  );
  return (
    <div>
      <div style={{ width: PHONE_WIDTH, padding: 12, border: "1px solid rgba(128,128,128,.35)", borderRadius: 20, background: "rgba(128,128,128,.06)" }}>
        <div style={{ display: "flex", gap: 12, overflow: "hidden" }}>
          <div style={{ width }}>
            {picture}
            {!spec.textOver && (
              <div style={{ paddingTop: 6 }}>
                <div style={{ fontWeight: 600 }}>{t}</div>
                {s ? <div style={{ fontSize: 12, opacity: 0.75 }}>{s}</div> : null}
                {app && placement === "FEATURED" ? (
                  <div style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 4, fontSize: 12 }}>
                    <Avatar size={18} shape="square" src={app.logoUrl ?? undefined}>
                      {app.slug[0]?.toUpperCase()}
                    </Avatar>
                    {i18nText(app.name) || app.slug}
                  </div>
                ) : null}
              </div>
            )}
          </div>
          {spec.widthPct < 100 && <div style={{ width: 60, height, borderRadius: 12, background: "rgba(128,128,128,.2)", flex: "none" }} />}
        </div>
      </div>
      <Typography.Text type="secondary" style={{ display: "block", maxWidth: PHONE_WIDTH, marginTop: 6 }}>
        {spec.note}
      </Typography.Text>
    </div>
  );
}
