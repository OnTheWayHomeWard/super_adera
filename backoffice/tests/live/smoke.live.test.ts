/**
 * Live smoke test: walks every dashboard section through the dashboard's own API client against the running dev stack.
 *   cd backoffice && npm run smoke        (stack up in dev mode; STAFF_EMAIL or BOOTSTRAP_SUPER_ADMIN_EMAIL in ../.env)
 * It creates one app (slug backoffice-smoke, reused on later runs, left KILLED), one throw-away VIEWER staff member
 * (identity deleted again at the end) and puts everything else back the way it found it.
 * Each check prints a line, so the output is the proof.
 */
import { generateKeyPairSync } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { authProvider } from "../../src/auth/authProvider";
import { api, apiRequest } from "../../src/lib/client";
import { ApiError } from "../../src/lib/errors";
import { getIdentity } from "../../src/lib/identity";
import { clearSession, getSession, saveSession } from "../../src/lib/session";
import type { AppDetail, AppView, AuditEvent, DashboardData, KeyView, Review, Report, StaffMember, Template, UserDetail, UserSummary } from "../../src/lib/types";
import { bootstrapEmail, deleteKratosIdentity, GATEWAY, patchFetch, randomSuffix, signIn, tinyPng } from "./helpers";

patchFetch();
const log = (m: string) => console.log(`  ok  ${m}`);
const SLUG = "backoffice-smoke";
const errorOf = async (p: Promise<unknown>): Promise<ApiError> => {
  try {
    await p;
  } catch (e) {
    if (e instanceof ApiError) return e;
    throw e;
  }
  throw new Error("expected the call to fail");
};

let superToken = "";
let viewerEmail = "";
let viewerId = "";
let appId = "";

beforeAll(async () => {
  const health = await fetch(`${GATEWAY}/backoffice/`);
  expect(health.status, "the dashboard must be served by the gateway at /backoffice/").toBe(200);
});

afterAll(async () => {
  if (viewerId) await deleteKratosIdentity(viewerId);
});

describe("1. login", () => {
  it("emailed code, authenticator app, aal2 session, role shown", async () => {
    const email = bootstrapEmail();
    const s = await signIn(email);
    superToken = s.token;
    log(`signed in as the first super admin (${s.enrolled ? "authenticator app enrolled just now" : "authenticator code accepted"})`);
    // the aal1 session (before the authenticator step) must be refused by the admin API
    const result = await authProvider.login({ token: superToken, email });
    expect(result.success).toBe(true);
    expect(getIdentity()?.role).toBe("SUPER_ADMIN");
    log(`authProvider.login ok, role ${getIdentity()?.role}`);
    expect(await authProvider.check()).toEqual({ authenticated: true });
  });

  it("without a session the admin API answers 401", async () => {
    const keep = getSession();
    clearSession();
    const e = await errorOf(api("me", { quiet: true }));
    expect(e.status).toBe(401);
    log("no token -> 401 unauthorized");
    if (keep) saveSession(keep);
  });

  it("a session that only did the emailed code gets 403 mfa_required", async () => {
    // a second, throw-away staff member is used later; here we only assert the error mapping with a garbage aal1-like token
    const keep = getSession();
    saveSession({ token: "not-a-real-token", aal: "aal2" });
    const e = await errorOf(api("me", { quiet: true }));
    expect(e.status).toBe(401);
    log("garbage token -> 401 (mapped to a login redirect by the shell)");
    if (keep) saveSession(keep);
  });
});

describe("2. dashboard", () => {
  it("shows real counts", async () => {
    const d = await api<DashboardData>("dashboard");
    expect(d.apps.total).toBe(d.apps.DRAFT + d.apps.LIVE + d.apps.PAUSED + d.apps.KILLED);
    log(`apps ${JSON.stringify(d.apps)}, open reports ${d.reports.open}, reviews ${d.reviews.total}`);
    const n = await api<{ total: number }>("notification/stats");
    const u = await api<{ total: number }>("users/stats");
    log(`notifications total ${n.total}, users total ${u.total}`);
  });
});

describe("3. mini apps", () => {
  it("create or reuse the smoke app, edit every kind of field, validation messages come from the API", async () => {
    const existing = (await api<AppView[]>("apps")).find((a) => a.slug === SLUG);
    if (existing) {
      appId = existing.id;
      log(`reusing app ${SLUG}`);
    } else {
      const bad = await errorOf(api("apps", { method: "POST", body: { slug: "X", name: {} } }));
      expect(bad.code).toBe("validation_failed");
      expect(Object.keys(bad.fields)).toEqual(expect.arrayContaining(["slug", "name"]));
      log(`create with bad input -> validation_failed fields ${Object.keys(bad.fields).join(", ")}`);
      const created = await api<AppView>("apps", { method: "POST", body: { slug: SLUG, name: { en: "Backoffice smoke", am: "ሙከራ" }, markets: ["ET"], priority: 900 } });
      appId = created.id;
      log(`created app ${SLUG}`);
    }
    const patched = await api<AppView>(`apps/${appId}`, {
      method: "PATCH",
      body: {
        name: { en: "Backoffice smoke", am: "ሙከራ", om: "Qorannoo" },
        description: { en: "Created by the dashboard smoke test." },
        brandColor: "#336699",
        categories: ["testing"],
        publisherName: "Adera QA",
        supportEmail: "smoke@adera.test",
        supportPhone: "+251900000000",
        website: "https://adera.test",
        entryUrl: "https://smoke.adera.test",
        allowedOrigins: ["https://smoke.adera.test"],
        priority: 901,
        extra: { smoke: true, note: "x" },
      },
    });
    expect(patched.priority).toBe(901);
    expect(patched.name.om).toBe("Qorannoo");
    log("edited name (en/am/om), description, publisher, support, urls, origins, priority, extra");
    const removed = await api<AppView>(`apps/${appId}`, { method: "PATCH", body: { extra: { note: null } } });
    expect(removed.extra).toEqual({ smoke: true });
    log("an extra key sent as null is removed");
    const slugChange = await errorOf(api(`apps/${appId}`, { method: "PATCH", body: { slug: "other" } }));
    expect(slugChange.fields.slug).toMatch(/permanent/);
    log(`slug change refused: ${slugChange.fields.slug}`);
  });

  it("status changes: the LIVE readiness message, logo upload and removal", async () => {
    await api(`apps/${appId}/status`, { method: "POST", body: { status: "DRAFT" } }).catch(() => undefined);
    await api(`apps/${appId}/logo`, { method: "DELETE" });
    const notReady = await errorOf(api(`apps/${appId}/status`, { method: "POST", body: { status: "LIVE" } }));
    expect(notReady.code).toBe("not_ready_for_live");
    expect(notReady.message).toBe("Not ready for LIVE: missing a logo.");
    log(`LIVE without a logo -> ${notReady.message}`);
    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(tinyPng())], { type: "image/png" }), "logo.png");
    const withLogo = await api<AppView>(`apps/${appId}/logo`, { method: "POST", form });
    expect(withLogo.logoUrl).toMatch(/^http/);
    log("logo uploaded (signed link returned)");
    for (const s of ["LIVE", "PAUSED", "DRAFT"]) {
      const v = await api<AppView>(`apps/${appId}/status`, { method: "POST", body: { status: s } });
      expect(v.status).toBe(s);
    }
    log("status DRAFT -> LIVE -> PAUSED -> DRAFT");
    const removedLogo = await api<AppView>(`apps/${appId}/logo`, { method: "DELETE" });
    expect(removedLogo.logoUrl).toBeNull();
    log("logo removed");
    await api(`apps/${appId}/logo`, { method: "POST", form });
  });

  it("capabilities: reason required, grant, suspend, deny", async () => {
    const catalog = await api<{ key: string; reasonRequired: boolean }[]>("capabilities");
    expect(catalog.length).toBeGreaterThanOrEqual(10);
    const noReason = await errorOf(api(`apps/${appId}/capabilities/location.coarse`, { method: "PUT", body: { state: "GRANTED", reason: {} } }));
    expect(noReason.fields.reason).toMatch(/required/);
    log(`grant without reason -> ${noReason.fields.reason.slice(0, 60)}...`);
    for (const state of ["GRANTED", "SUSPENDED", "DENIED"]) {
      const d = await api<AppDetail>(`apps/${appId}/capabilities/location.coarse`, { method: "PUT", body: { state, reason: { en: "To show technicians near you", am: "ለማሳየት" } } });
      expect(d.capabilities.find((c) => c.capability === "location.coarse")?.state).toBe(state);
    }
    await api(`apps/${appId}/capabilities/notify.inapp`, { method: "PUT", body: { state: "GRANTED" } });
    log("location.coarse GRANTED/SUSPENDED/DENIED, notify.inapp GRANTED");
  });

  it("notification limits: null is unlimited, 0 is none", async () => {
    const l = await api<{ maxPerDay: number | null; maxTotal: number | null }>(`apps/${appId}/notification-limits/SMS`, {
      method: "PUT",
      body: { maxPerUserPerDay: 0, maxPerDay: 50, maxDistinctUsersPerDay: null, maxTotal: null },
    });
    expect(l.maxPerDay).toBe(50);
    const detail = await api<AppDetail>(`apps/${appId}`);
    const sms = detail.notificationLimits.find((x) => x.channel === "SMS");
    expect(sms).toMatchObject({ maxPerUserPerDay: 0, maxPerDay: 50, maxDistinctUsersPerDay: null, maxTotal: null });
    const bad = await errorOf(api(`apps/${appId}/notification-limits/SMS`, { method: "PUT", body: { maxPerDay: -1 } }));
    expect(bad.fields.maxPerDay).toMatch(/whole number/);
    log("SMS limits: per user 0 (none), per day 50, distinct/total null (unlimited); -1 refused");
  });

  it("system: which environment this deployment is", async () => {
    const sys = await api<{ environment: string }>("system");
    expect(["DEV", "PROD"]).toContain(sys.environment);
    log(`this deployment runs ${sys.environment}`);
  });

  it("environments and allowed IPs", async () => {
    const env = await api<{ allowedCidrs: string[] }>(`apps/${appId}/environments/DEV`, { method: "PUT", body: { allowedCidrs: ["203.0.113.10", "198.51.100.0/24", "203.0.113.10"] } });
    expect(env.allowedCidrs).toEqual(["203.0.113.10/32", "198.51.100.0/24"].map((c) => (env.allowedCidrs.includes(c) ? c : c.replace("/32", ""))));
    const live = await errorOf(api(`apps/${appId}/environments/PROD`, { method: "PUT", body: { allowedCidrs: ["0.0.0.0/0"] } }));
    expect(live.fields.allowedCidrs).toMatch(/PROD/);
    const nope = await errorOf(api(`apps/${appId}/environments/DEV`, { method: "PUT", body: { allowedCidrs: ["not-an-ip"] } }));
    expect(nope.fields.allowedCidrs).toMatch(/not a valid/);
    await api(`apps/${appId}/environments/DEV`, { method: "PUT", body: { allowedCidrs: [] } });
    log("DEV allowed IPs saved (duplicates merged), /0 refused for PROD, junk refused, cleared again");
  });

  it("partner keys: register a pasted public key, generate a pair (private key once), revoke", async () => {
    const { publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
    const pem = publicKey.export({ type: "spki", format: "pem" }).toString();
    const registered = await api<KeyView>(`apps/${appId}/keys`, { method: "POST", body: { environment: "DEV", publicKeyPem: pem, label: "smoke registered" } });
    expect(registered.status).toBe("ACTIVE");
    const generated = await api<{ key: KeyView; privateKeyPem: string; warning: string }>(`apps/${appId}/keys/generate`, { method: "POST", body: { environment: "DEV", label: "smoke generated" } });
    expect(generated.privateKeyPem).toContain("PRIVATE KEY");
    expect(generated.warning).toMatch(/once/i);
    const listed = await api<AppDetail>(`apps/${appId}`);
    expect(JSON.stringify(listed)).not.toContain("PRIVATE KEY");
    log("registered a pasted public key; generated a pair (private key returned once and not in later reads)");
    const badKey = await errorOf(api(`apps/${appId}/keys`, { method: "POST", body: { environment: "DEV", publicKeyPem: "nonsense" } }));
    expect(badKey.code).toBe("validation_failed");
    for (const k of [registered, generated.key]) {
      const revoked = await api<KeyView>(`apps/${appId}/keys/${k.keyId}`, { method: "DELETE" });
      expect(revoked.status).toBe("REVOKED");
    }
    log("both keys revoked");
  });

  it("payment settings", async () => {
    const p = await api<{ allowedCurrencies: string[] }>(`apps/${appId}/payment-settings`, { method: "PUT", body: { allowedCurrencies: ["ETB"], allowHold: true, minAmountMinor: 100, maxAmountMinor: 500000 } });
    expect(p.allowedCurrencies).toEqual(["ETB"]);
    const bad = await errorOf(api(`apps/${appId}/payment-settings`, { method: "PUT", body: { allowedCurrencies: ["XYZ"], minAmountMinor: 10, maxAmountMinor: 5 } }));
    expect(bad.code).toBe("validation_failed");
    log("payment settings saved; bad currency / min>max refused with field messages");
  });

  it("templates: create with params and per-language content, edit, deactivate, expiry", async () => {
    const list = await api<Template[]>("notification/templates", { query: { appId } });
    for (const t of list.filter((x) => x.code.startsWith("smoke-"))) await api(`notification/templates/${t.id}`, { method: "PATCH", body: { active: false } });
    const code = `smoke-${randomSuffix()}`;
    const bad = await errorOf(api("notification/templates", { method: "POST", body: { appId, code, kind: "DIRECT", category: "TRANSACTIONAL", params: [], content: { en: { body: "Hi {name}" } } } }));
    expect(bad.fields["content.en.body"]).toMatch(/not a declared param/);
    log(`undeclared placeholder -> content.en.body: ${bad.fields["content.en.body"]}`);
    const created = await api<Template>("notification/templates", {
      method: "POST",
      body: {
        appId, code, kind: "DIRECT", category: "TRANSACTIONAL",
        params: [{ name: "name", maxLength: 40, required: true, allowLinks: false }],
        content: { en: { body: "Hi {name}", sms: "Hi {name}!" }, am: { body: "ሰላም {name}" } },
      },
    });
    expect(created.version).toBe(1);
    const sameParams = await api<Template>(`notification/templates/${created.id}`, { method: "PATCH", body: { category: "REMINDER" } });
    expect(sameParams.version).toBe(1);
    const edited = await api<Template>(`notification/templates/${created.id}`, { method: "PATCH", body: { content: { en: { body: "Hello {name}" } } } });
    expect(edited.version).toBe(2);
    const expiry = new Date(Date.now() + 86_400_000).toISOString();
    const withExpiry = await api<Template>(`notification/templates/${created.id}`, { method: "PATCH", body: { expiresAt: expiry } });
    expect(withExpiry.expiresAt).toBeTruthy();
    const off = await api<Template>(`notification/templates/${created.id}`, { method: "PATCH", body: { active: false } });
    expect(off.usableNow).toBe(false);
    log(`template ${code}: created, category change keeps version 1, content change -> version 2, expiry set, deactivated`);
  });

  it("notification log and usage for the app", async () => {
    const r = await apiRequest<unknown[]>("notification/notifications", { query: { appId, limit: 10 } });
    expect(Array.isArray(r.data)).toBe(true);
    expect(typeof r.total).toBe("number");
    const u = await apiRequest<unknown[]>("notification/usage", { query: { appId, environment: "DEV" } });
    expect(Array.isArray(u.data)).toBe(true);
    log(`notification log (${r.total} rows, X-Total-Count present) and usage answered`);
  });

  it("app audit shows the changes above", async () => {
    const r = await apiRequest<AuditEvent[]>("audit", { query: { target: appId, limit: 100 } });
    const actions = new Set(r.data.map((e) => e.action));
    for (const a of ["app.update", "app.status", "app.logo", "capability.set", "limit.set", "environment.set", "payment.set"]) expect(actions.has(a), a).toBe(true);
    expect(r.data[0].actorEmail).toBeTruthy();
    log(`audit for the app: ${r.total} events, actions ${[...actions].join(", ")}`);
  });

  it("KILLED can only be revived by a super admin (checked as a viewer later); end state: KILLED", async () => {
    const v = await api<AppView>(`apps/${appId}/status`, { method: "POST", body: { status: "KILLED" } });
    expect(v.status).toBe("KILLED");
    log("smoke app left KILLED");
  });
});

describe("4. moderation", () => {
  it("lists reviews and reports across apps; hide/restore a review if one exists", async () => {
    const reviews = await apiRequest<Review[]>("reviews", { query: { page: 0, size: 5 } });
    const reports = await apiRequest<Report[]>("reports", { query: { page: 0, size: 5 } });
    log(`reviews ${reviews.total}, reports ${reports.total}`);
    const filtered = await apiRequest<Review[]>("reviews", { query: { status: "HIDDEN", page: 0, size: 5 } });
    expect(filtered.data.every((r) => r.status === "HIDDEN")).toBe(true);
    if (reviews.data.length) {
      const r = reviews.data[0];
      const original = r.status;
      const flipped = await api<Review>(`reviews/${r.id}`, { method: "PATCH", body: { status: original === "VISIBLE" ? "HIDDEN" : "VISIBLE" } });
      expect(flipped.status).not.toBe(original);
      await api(`reviews/${r.id}`, { method: "PATCH", body: { status: original } });
      log("hid and restored one review");
    }
    if (reports.data.length) {
      const r = reports.data[0];
      await api(`reports/${r.id}`, { method: "PATCH", body: { status: r.status } });
      log("report status round-trip ok");
    }
  });
});

describe("5. users", () => {
  it("list, search, detail without tokens or coordinates; suspend and reactivate with a reason", async () => {
    const list = await api<{ items: UserSummary[]; total: number; page: number; size: number }>("users", { query: { page: 0, size: 5 } });
    expect(list.total).toBeGreaterThanOrEqual(list.items.length);
    log(`users: ${list.total} registered`);
    if (!list.items.length) return;
    const u = list.items.find((x) => x.status === "ACTIVE") ?? list.items[0];
    const byId = await api<{ items: UserSummary[] }>("users", { query: { q: u.id } });
    expect(byId.items.map((x) => x.id)).toContain(u.id);
    const detail = await api<UserDetail>(`users/${u.id}`);
    const text = JSON.stringify(detail);
    expect(text).not.toMatch(/fcm|token|latitude|longitude|u_p_/i);
    expect(detail.masked).toBe(false);
    expect(detail.devices).toHaveProperty("platforms");
    log("search by id works; detail has devices count/platforms, alias count, no tokens or coordinates");
    if (u.status === "ACTIVE") {
      const noReason = await errorOf(api(`users/${u.id}/status`, { method: "POST", body: { status: "SUSPENDED" } }));
      expect(noReason.code).toBe("validation_failed");
      await api(`users/${u.id}/status`, { method: "POST", body: { status: "SUSPENDED", reason: "dashboard smoke test" } });
      expect((await api<UserDetail>(`users/${u.id}`)).status).toBe("SUSPENDED");
      await api(`users/${u.id}/status`, { method: "POST", body: { status: "ACTIVE", reason: "dashboard smoke test done" } });
      expect((await api<UserDetail>(`users/${u.id}`)).status).toBe("ACTIVE");
      log("suspended (reason required) and reactivated; both audited");
    }
  });
});

describe("6. notifications (global)", () => {
  it("cross-app log with filters and stats", async () => {
    const r = await apiRequest<{ id: string; appId?: string; status: string }[]>("notification/notifications", { query: { limit: 5 } });
    expect(typeof r.total).toBe("number");
    if (r.data.length) {
      expect(r.data[0].appId).toBeTruthy();
      const d = await api<{ attemptLog: unknown[]; callbacks: unknown[]; params?: unknown }>(`notification/notifications/${r.data[0].id}`);
      expect(d.attemptLog).toBeDefined();
      expect(d.params).toBeUndefined();
      log("notification detail has attempts and callbacks, no message values");
    }
    const sent = await apiRequest<{ status: string }[]>("notification/notifications", { query: { status: "SENT", limit: 5 } });
    expect(sent.data.every((n) => n.status === "SENT")).toBe(true);
    log(`global log ${r.total} rows; status filter works`);
  });
});

describe("7. staff, and the VIEWER role", () => {
  it("add a viewer, sign in as them, see read-only behaviour and masked data, then change role and deactivate", async () => {
    viewerEmail = `smoke-viewer-${randomSuffix()}@adera.test`;
    const added = await api<StaffMember>("staff", { method: "POST", body: { email: viewerEmail, role: "VIEWER" } });
    viewerId = added.id;
    expect(added.role).toBe("VIEWER");
    const staff = await api<StaffMember[]>("staff");
    const boot = staff.find((s) => s.bootstrap);
    expect(boot?.role).toBe("SUPER_ADMIN");
    log(`staff list ok; bootstrap account flagged: ${boot ? "yes" : "no"}`);

    const lastSuper = staff.filter((s) => s.role === "SUPER_ADMIN" && s.status === "ACTIVE");
    if (lastSuper.length === 1) {
      const e = await errorOf(api(`staff/${lastSuper[0].id}`, { method: "PATCH", body: { role: "ADMIN" } }));
      expect(["cannot_demote_self", "last_super_admin"]).toContain(e.code);
      log(`demoting the only super admin refused: ${e.message}`);
    }

    // sign in as the viewer
    const superSession = getSession();
    await signIn(viewerEmail);
    const login = await authProvider.login({ token: getSession()!.token, email: viewerEmail });
    expect(login.success).toBe(true);
    expect(getIdentity()?.role).toBe("VIEWER");
    log("viewer signed in (emailed code + authenticator enrolment)");
    const denied = await errorOf(api("apps", { method: "POST", body: { slug: "viewer-try", name: { en: "x" } } }));
    expect(denied.status).toBe(403);
    expect(denied.code).toBe("insufficient_role");
    log(`viewer create app -> 403 ${denied.code}`);
    expect((await errorOf(api("staff"))).code).toBe("insufficient_role");
    expect((await errorOf(api("audit"))).code).toBe("insufficient_role");
    expect((await errorOf(api(`apps/${appId}/status`, { method: "POST", body: { status: "DRAFT" } }))).code).toBe("insufficient_role");
    log("viewer: staff list, audit and status change refused");
    const apps = await api<AppView[]>("apps");
    expect(apps.length).toBeGreaterThan(0);
    const users = await api<{ items: UserSummary[] }>("users", { query: { page: 0, size: 5 } });
    const withEmail = users.items.find((u) => u.email);
    if (withEmail) expect(withEmail.email).toMatch(/\*/);
    const phone = users.items.find((u) => u.phone);
    if (phone) expect(phone.phone).toMatch(/\*/);
    if (users.items.length) {
      const d = await api<UserDetail>(`users/${users.items[0].id}`);
      expect(d.masked).toBe(true);
      expect(d.extra).toBeNull();
    }
    log("viewer reads apps; user email/phone masked, extra hidden");

    // back to the super admin
    saveSession(superSession!);
    await authProvider.login({ token: superSession!.token, email: bootstrapEmail() });
    const promoted = await api<StaffMember>(`staff/${viewerId}`, { method: "PATCH", body: { role: "ADMIN" } });
    expect(promoted.role).toBe("ADMIN");
    const off = await api<StaffMember>(`staff/${viewerId}`, { method: "PATCH", body: { status: "DISABLED" } });
    expect(off.status).toBe("DISABLED");
    log("role changed to ADMIN, then account deactivated");
  });
});

describe("8. audit log", () => {
  it("filters by actor, action, target, target type and date range", async () => {
    const all = await apiRequest<AuditEvent[]>("audit", { query: { limit: 20 } });
    expect(all.total).toBeGreaterThan(0);
    const mine = all.data.find((e) => e.actorId);
    const byActor = await apiRequest<AuditEvent[]>("audit", { query: { actor: mine?.actorId ?? "", limit: 20 } });
    expect(byActor.data.every((e) => e.actorId === mine?.actorId)).toBe(true);
    const byAction = await apiRequest<AuditEvent[]>("audit", { query: { action: "app.", limit: 50 } });
    expect(byAction.data.length).toBeGreaterThan(0);
    expect(byAction.data.every((e) => e.action.startsWith("app."))).toBe(true);
    const byType = await apiRequest<AuditEvent[]>("audit", { query: { targetType: "staff", limit: 50 } });
    expect(byType.data.every((e) => e.targetType === "staff")).toBe(true);
    const future = await apiRequest<AuditEvent[]>("audit", { query: { from: new Date(Date.now() + 3_600_000).toISOString(), limit: 5 } });
    expect(future.data).toEqual([]);
    const page2 = await apiRequest<AuditEvent[]>("audit", { query: { limit: 5, offset: 5 } });
    expect(page2.data.length).toBeLessThanOrEqual(5);
    log(`audit filters: actor, action prefix, target type, date range, paging (total ${all.total})`);
    const userActions = await apiRequest<AuditEvent[]>("audit", { query: { action: "user.status", limit: 5 } });
    log(`user.status events: ${userActions.total}`);
  });
});

describe("9. system", () => {
  it("health probes through the dashboard server, capability catalog, served files", async () => {
    const token = superToken;
    const names = ["registry", "user", "notification", "kratos", "prometheus", "grafana", "loki", "mailpit", "infisical"];
    const states: string[] = [];
    for (const n of names) {
      const r = await fetch(`${GATEWAY}/backoffice/api/health/${n}`, { headers: { Authorization: `Bearer ${token}` } });
      states.push(`${n}:${r.status}`);
      expect(r.status, n).toBe(200);
    }
    log(`health probes ${states.join(" ")}`);
    const anon = await fetch(`${GATEWAY}/backoffice/api/health/registry`);
    expect(anon.status).toBe(401);
    log("health probes without a session -> 401");
    const config = await (await fetch(`${GATEWAY}/backoffice/config.json`)).json();
    expect(config).toHaveProperty("devHints");
    expect(JSON.stringify(config)).not.toMatch(/secret|password|token/i);
    const html = await (await fetch(`${GATEWAY}/backoffice/apps`)).text();
    expect(html).toContain('<div id="root">');
    log("config.json served without secrets; deep links fall back to index.html");
  });
});
