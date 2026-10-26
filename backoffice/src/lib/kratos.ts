/**
 * The staff sign-in, step by step, against Ory Kratos' native (API) flows through the gateway:
 * emailed one-time code (aal1) -> first time: enrol an authenticator app (TOTP) / afterwards: enter a TOTP code (aal2).
 * Same calls as tests/e2e/staff.py. The gateway allows 10 login submits per minute per IP (429 with an HTML page).
 */
export const KRATOS_BASE = "/kratos";

export class KratosError extends Error {
  readonly status: number;
  readonly kind: "rate_limited" | "flow_expired" | "rejected" | "network" | "unexpected";
  readonly messages: string[];

  constructor(status: number, kind: KratosError["kind"], messages: string[] = []) {
    super(messages[0] ?? kind);
    this.name = "KratosError";
    this.status = status;
    this.kind = kind;
    this.messages = messages;
  }
}

export function kratosErrorText(error: unknown): string {
  if (error instanceof KratosError) {
    switch (error.kind) {
      case "rate_limited":
        return "Too many sign-in attempts from this address (the limit is 10 per minute). Wait a minute and try again.";
      case "flow_expired":
        return "This sign-in attempt expired. Start again.";
      case "network":
        return "The server could not be reached. Check your connection and that the gateway is running.";
      default:
        return error.messages.length ? error.messages.join(" ") : "Sign-in failed. Try again.";
    }
  }
  return error instanceof Error ? error.message : "Sign-in failed.";
}

interface UiMessage {
  text?: string;
}
interface UiNode {
  attributes?: Record<string, unknown>;
  messages?: UiMessage[];
}
interface Flow {
  id?: string;
  state?: string;
  session_token?: string;
  ui?: { messages?: UiMessage[]; nodes?: UiNode[] };
  error?: { id?: string; reason?: string; message?: string };
}

/** Every human-readable message Kratos put on the flow (top level and per input). */
export function flowMessages(flow: Flow): string[] {
  const out: string[] = [];
  for (const m of flow.ui?.messages ?? []) if (m.text) out.push(m.text);
  for (const node of flow.ui?.nodes ?? []) for (const m of node.messages ?? []) if (m.text) out.push(m.text);
  return [...new Set(out)];
}

type Fetcher = typeof fetch;
interface Result {
  status: number;
  json: Flow;
}

async function call(fetcher: Fetcher, method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<Result> {
  let response: Response;
  try {
    response = await fetcher(`${KRATOS_BASE}${path}`, {
      method,
      headers: { Accept: "application/json", ...(body !== undefined ? { "Content-Type": "application/json" } : {}), ...headers },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new KratosError(0, "network");
  }
  if (response.status === 429) throw new KratosError(429, "rate_limited");
  const text = await response.text();
  let json: Flow = {};
  try {
    json = text ? (JSON.parse(text) as Flow) : {};
  } catch {
    json = {};
  }
  if (response.status === 410) throw new KratosError(410, "flow_expired", flowMessages(json));
  return { status: response.status, json };
}

function rejected(result: Result, fallback: string): KratosError {
  const messages = flowMessages(result.json);
  if (!messages.length && result.json.error) {
    messages.push(result.json.error.reason ?? result.json.error.message ?? fallback);
  }
  return new KratosError(result.status, "rejected", messages.length ? messages : [fallback]);
}

export interface Kratos {
  startLogin(): Promise<string>;
  sendCode(flowId: string, email: string): Promise<void>;
  submitCode(flowId: string, email: string, code: string): Promise<string>;
  whoami(token: string): Promise<{ state: "aal1_no_totp" | "aal2_required" | "aal2" | "invalid"; email?: string }>;
  startEnrolment(token: string): Promise<{ flowId: string; secret: string }>;
  confirmEnrolment(token: string, flowId: string, code: string): Promise<void>;
  startSecondFactor(token: string): Promise<string>;
  submitSecondFactor(token: string, flowId: string, code: string): Promise<string>;
  logout(token: string): Promise<void>;
}

export function createKratos(fetcher: Fetcher = (...args) => fetch(...args)): Kratos {
  return {
    async startLogin() {
      const r = await call(fetcher, "GET", "/self-service/login/api");
      if (!r.json.id) throw rejected(r, "Could not start a sign-in.");
      return r.json.id;
    },

    async sendCode(flowId, email) {
      const r = await call(fetcher, "POST", `/self-service/login?flow=${encodeURIComponent(flowId)}`, { method: "code", identifier: email });
      // Kratos answers 400 with state "sent_email" when the code was sent; anything else is a refusal
      if (r.json.state === "sent_email") return;
      throw rejected(r, "The code could not be sent.");
    },

    async submitCode(flowId, email, code) {
      const r = await call(fetcher, "POST", `/self-service/login?flow=${encodeURIComponent(flowId)}`, { method: "code", identifier: email, code });
      if (r.status === 200 && r.json.session_token) return r.json.session_token;
      throw rejected(r, "That code is not valid.");
    },

    async whoami(token) {
      const r = await call(fetcher, "GET", "/sessions/whoami", undefined, { "X-Session-Token": token });
      if (r.status === 401) return { state: "invalid" };
      if (r.status === 403) return { state: "aal2_required" };
      if (r.status !== 200) throw rejected(r, "Could not check the session.");
      const session = r.json as Flow & { authenticator_assurance_level?: string; identity?: { traits?: { email?: string } } };
      const email = session.identity?.traits?.email;
      return { state: session.authenticator_assurance_level === "aal2" ? "aal2" : "aal1_no_totp", email };
    },

    async startEnrolment(token) {
      const r = await call(fetcher, "GET", "/self-service/settings/api", undefined, { Authorization: `Bearer ${token}` });
      let secret: string | undefined;
      for (const node of r.json.ui?.nodes ?? []) {
        if (node.attributes?.id === "totp_secret_key") {
          secret = (node.attributes.text as { text?: string } | undefined)?.text;
        }
      }
      if (!r.json.id || !secret) throw rejected(r, "Could not start the authenticator setup.");
      return { flowId: r.json.id, secret };
    },

    async confirmEnrolment(token, flowId, code) {
      const r = await call(fetcher, "POST", `/self-service/settings?flow=${encodeURIComponent(flowId)}`, { method: "totp", totp_code: code }, { Authorization: `Bearer ${token}` });
      if (r.status === 200 || r.status === 303 || r.json.state === "success") return;
      throw rejected(r, "That authenticator code is not valid.");
    },

    async startSecondFactor(token) {
      const r = await call(fetcher, "GET", "/self-service/login/api?aal=aal2&refresh=false", undefined, { "X-Session-Token": token });
      if (!r.json.id) throw rejected(r, "Could not start the authenticator step.");
      return r.json.id;
    },

    async submitSecondFactor(token, flowId, code) {
      const r = await call(fetcher, "POST", `/self-service/login?flow=${encodeURIComponent(flowId)}`, { method: "totp", totp_code: code }, { "X-Session-Token": token });
      if (r.status === 200) return r.json.session_token ?? token;
      throw rejected(r, "That authenticator code is not valid.");
    },

    async logout(token) {
      try {
        await call(fetcher, "DELETE", "/self-service/logout/api", { session_token: token });
      } catch {
        // the local session is cleared either way
      }
    },
  };
}

export const kratos = createKratos();

/** The text for an authenticator app: scan the QR code or type the secret. */
export function otpauthUri(secret: string, email: string, issuer = "Adera Backoffice"): string {
  const label = encodeURIComponent(`${issuer}:${email}`);
  return `otpauth://totp/${label}?secret=${encodeURIComponent(secret)}&issuer=${encodeURIComponent(issuer)}`;
}
