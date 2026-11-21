// Banner helpers (docs/guides/app-banners/): image proxy URLs, language fallback, image size choice, field checks and
// "what the super app should do" for each action type. Browser: window.LabBanners. Node: module.exports.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.LabBanners = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";
  const PLACEMENTS = ["HOME_HERO", "FEATURED", "PROMOTIONS", "CATALOG_TOP"];
  const CATEGORIES = ["PROMOTION", "FEATURED_APP", "ANNOUNCEMENT", "EVENT"];
  const ACTIONS = ["MINI_APP", "INTERNAL", "EXTERNAL", "NONE"];
  const SIZES = { small: 480, medium: 960, large: 1600 };
  const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

  // every image goes through the Bridge Lab backend: never load plain http from an https page
  const imgUrl = (u) => (u ? "/lab/img?u=" + encodeURIComponent(u) : "");

  // the user's language, else English; says whether it fell back
  function pickText(map, lang) {
    if (!isObj(map)) return { text: "", lang: null, fallback: false };
    if (map[lang]) return { text: map[lang], lang, fallback: false };
    if (map.en) return { text: map.en, lang: "en", fallback: lang !== "en" };
    const k = Object.keys(map)[0];
    return { text: k ? map[k] : "", lang: k || null, fallback: true };
  }

  // the smallest size whose width covers the slot in device pixels
  function pickSize(images, cssWidth, dpr) {
    const need = Math.ceil((cssWidth || 360) * (dpr || 1));
    for (const k of ["small", "medium", "large"]) if (images && images[k] && images[k].width >= need) return k;
    return images && images.large ? "large" : images && images.medium ? "medium" : "small";
  }

  // a route goes into the path of the launch url, keeping its query (app-banners/actions.html#mini-app)
  function joinRoute(launchUrl, route) {
    const u = new URL(launchUrl);
    if (!route) return u.toString();
    const r = new URL(route, "http://route.invalid");
    const base = u.pathname.replace(/\/+$/, "");
    u.pathname = base + r.pathname;
    const merged = new URLSearchParams(r.search);
    new URLSearchParams(u.search).forEach((v, k) => merged.append(k, v));
    u.search = merged.toString();
    return u.toString();
  }

  // problems with one banner against the documented shape
  function validate(b, now) {
    const p = [];
    now = now || Date.now();
    if (!isObj(b)) return ["not an object"];
    if (!b.id) p.push("no id");
    if (!isObj(b.title) || !b.title.en) p.push("title.en missing (English is always there)");
    if (!PLACEMENTS.includes(b.placement)) p.push("placement " + b.placement);
    if (!CATEGORIES.includes(b.category)) p.push("category " + b.category);
    if (!ACTIONS.includes(b.actionType)) p.push("actionType " + b.actionType);
    if (b.actionType === "MINI_APP") {
      if (!b.app || !b.app.slug) p.push("MINI_APP without a linked app");
      if (b.target != null && !(typeof b.target === "string" && b.target[0] === "/" && b.target[1] !== "/")) p.push("MINI_APP target must be a route starting with /");
    }
    if (b.actionType === "INTERNAL" && !/^adera:\/\//.test(b.target || "")) p.push("INTERNAL target must be adera://...");
    if (b.actionType === "EXTERNAL" && !/^https:\/\//.test(b.target || "")) p.push("EXTERNAL target must be https://...");
    if (b.actionType === "NONE" && b.target != null) p.push("NONE with a target");
    if (!isObj(b.images)) p.push("no images");
    else for (const [k, w] of Object.entries(SIZES)) {
      const im = b.images[k];
      if (!im || !im.url) p.push("image " + k + " missing");
      else if (Math.max(im.width || 0, im.height || 0) !== w) p.push("image " + k + " longest side " + Math.max(im.width || 0, im.height || 0) + " (documented " + w + ")");
    }
    if (b.startsAt && Date.parse(b.startsAt) > now + 60000) p.push("starts in the future (" + b.startsAt + ") but is listed");
    if (b.endsAt && Date.parse(b.endsAt) < now - 60000) p.push("ended (" + b.endsAt + ") but is listed");
    return p;
  }

  // what the super app should do when this banner is tapped
  function advice(b, cfg) {
    cfg = cfg || {};
    const steps = [];
    const problems = validate(b).filter((x) => /target|app/.test(x));
    if (b.actionType === "MINI_APP") {
      const slug = b.app && b.app.slug;
      steps.push("The user must be signed in (sign in first, then continue).");
      steps.push("Launch the app exactly as from the catalog: POST /api/v1/me/apps/" + slug + "/launch (consent and token as usual; the banner gives no extra rights).");
      if (b.target) {
        steps.push("Put the route " + b.target + " into the path of the returned url, keep its query, exactly one slash, same host.");
        const example = slug === cfg.slug && cfg.entryUrl ? joinRoute(cfg.entryUrl + (cfg.entryUrl.indexOf("?") >= 0 ? "&" : "?") + "launch=<token>", b.target) : joinRoute("https://app.example/?launch=<token>", b.target);
        steps.push("Example: " + example.replace("%3Ctoken%3E", "<token>"));
      } else steps.push("No route: load the url as it is (the app's home).");
      steps.push("If the launch is refused (403 app_not_live), show the usual \"app unavailable\" message.");
    } else if (b.actionType === "INTERNAL") {
      steps.push("Hand " + b.target + " to the super app's own deep-link router (the one used for adera:// links from notifications).");
      steps.push("An unknown link does nothing or opens the home screen; never crash.");
    } else if (b.actionType === "EXTERNAL") {
      steps.push("Open " + b.target + " in the system browser or an in-app browser tab (Custom Tabs / SFSafariViewController).");
      steps.push("Never inside a mini-app WebView, never with the user's session or the bridge; check https:// again before opening.");
    } else {
      steps.push("Nothing: the banner is not tappable; show no pressed state.");
    }
    return { actionType: b.actionType, steps, problems };
  }

  return { PLACEMENTS, CATEGORIES, ACTIONS, SIZES, imgUrl, pickText, pickSize, joinRoute, validate, advice };
});
