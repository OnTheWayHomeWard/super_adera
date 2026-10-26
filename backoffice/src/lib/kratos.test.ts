import { describe, expect, it, vi } from "vitest";
import { createKratos, flowMessages, KratosError, kratosErrorText, otpauthUri } from "./kratos";

function seq(...responses: { status: number; body?: unknown; text?: string }[]) {
  const fetcher = vi.fn();
  for (const r of responses) {
    fetcher.mockResolvedValueOnce(new Response(r.text ?? JSON.stringify(r.body ?? {}), { status: r.status }));
  }
  return fetcher;
}

describe("kratos login flow", () => {
  it("sends the code: 400 with state sent_email is success", async () => {
    const f = seq({ status: 400, body: { state: "sent_email" } });
    await createKratos(f as never).sendCode("flow1", "a@b.co");
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("/kratos/self-service/login?flow=flow1");
    expect(JSON.parse(init.body)).toEqual({ method: "code", identifier: "a@b.co" });
  });

  it("an unknown account is reported with Kratos' own message", async () => {
    const f = seq({ status: 400, body: { ui: { messages: [{ text: "This account does not exist" }] } } });
    await expect(createKratos(f as never).sendCode("f", "x@y.co")).rejects.toMatchObject({ kind: "rejected", messages: ["This account does not exist"] });
  });

  it("429 from the gateway (HTML page) becomes a clear rate-limit message", async () => {
    const f = seq({ status: 429, text: "<html>Too Many Requests</html>" });
    const error = await createKratos(f as never).startLogin().catch((e) => e);
    expect(error).toBeInstanceOf(KratosError);
    expect(error.kind).toBe("rate_limited");
    expect(kratosErrorText(error)).toMatch(/10 per minute/);
  });

  it("an expired flow (410) is flagged so the screen starts over", async () => {
    const f = seq({ status: 410, body: {} });
    await expect(createKratos(f as never).submitCode("f", "a@b.co", "123456")).rejects.toMatchObject({ kind: "flow_expired" });
  });

  it("a wrong code surfaces the node message", async () => {
    const f = seq({ status: 400, body: { ui: { nodes: [{ messages: [{ text: "The login code is invalid or has expired." }] }] } } });
    await expect(createKratos(f as never).submitCode("f", "a@b.co", "000000")).rejects.toMatchObject({ messages: ["The login code is invalid or has expired."] });
  });

  it("returns the session token on success", async () => {
    const f = seq({ status: 200, body: { session_token: "tok" } });
    expect(await createKratos(f as never).submitCode("f", "a@b.co", "123456")).toBe("tok");
  });
});

describe("second factor", () => {
  it("whoami tells the three situations apart", async () => {
    const k = (s: number, b: unknown) => createKratos(seq({ status: s, body: b }) as never).whoami("t");
    expect(await k(200, { authenticator_assurance_level: "aal1", identity: { traits: { email: "a@b.co" } } })).toEqual({ state: "aal1_no_totp", email: "a@b.co" });
    expect(await k(403, { error: { id: "session_aal2_required" } })).toEqual({ state: "aal2_required" });
    expect(await k(200, { authenticator_assurance_level: "aal2" })).toMatchObject({ state: "aal2" });
    expect(await k(401, {})).toEqual({ state: "invalid" });
  });

  it("reads the TOTP secret from the settings flow", async () => {
    const flow = { id: "set1", ui: { nodes: [{ attributes: { id: "totp_secret_key", text: { text: "JBSWY3DPEHPK3PXP" } } }, { attributes: { id: "totp_qr" } }] } };
    const f = seq({ status: 200, body: flow });
    expect(await createKratos(f as never).startEnrolment("t")).toEqual({ flowId: "set1", secret: "JBSWY3DPEHPK3PXP" });
    expect(f.mock.calls[0][1].headers.Authorization).toBe("Bearer t");
  });

  it("submits the authenticator code on an aal2 flow with the session token header", async () => {
    const f = seq({ status: 200, body: { session_token: "t2" } });
    expect(await createKratos(f as never).submitSecondFactor("t1", "flowX", "123456")).toBe("t2");
    const [, init] = f.mock.calls[0];
    expect(init.headers["X-Session-Token"]).toBe("t1");
    expect(JSON.parse(init.body)).toEqual({ method: "totp", totp_code: "123456" });
  });

  it("a rejected authenticator code is an error with Kratos' message", async () => {
    const f = seq({ status: 400, body: { ui: { messages: [{ text: "The provided authentication code is invalid" }] } } });
    await expect(createKratos(f as never).submitSecondFactor("t", "f", "000000")).rejects.toMatchObject({ kind: "rejected" });
  });
});

describe("helpers", () => {
  it("builds an otpauth link", () => {
    expect(otpauthUri("ABC", "a@b.co")).toBe("otpauth://totp/Adera%20Backoffice%3Aa%40b.co?secret=ABC&issuer=Adera%20Backoffice");
  });
  it("collects unique flow messages", () => {
    expect(flowMessages({ ui: { messages: [{ text: "a" }], nodes: [{ messages: [{ text: "a" }, { text: "b" }] }] } })).toEqual(["a", "b"]);
  });
});
