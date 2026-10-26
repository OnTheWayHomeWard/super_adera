import { useLogin } from "@refinedev/core";
import { Alert, Button, Card, Form, Input, Space, Steps, Typography } from "antd";
import QRCode from "qrcode";
import { useCallback, useEffect, useState } from "react";
import { consumeLoginNotice, type LoginNotice } from "../auth/notice";
import { CopyButton } from "../components/common";
import { getConfig } from "../lib/config";
import { KratosError, kratos, kratosErrorText, otpauthUri } from "../lib/kratos";
import { clearSession, getSession, saveSession } from "../lib/session";

type Step = "email" | "code" | "enrol" | "totp" | "finishing";

interface Enrolment {
  flowId: string;
  secret: string;
  uri: string;
  qr: string;
}

const STEP_INDEX: Record<Step, number> = { email: 0, code: 1, enrol: 2, totp: 2, finishing: 2 };

/**
 * Staff sign-in: email -> emailed code -> (first time) set up an authenticator app -> authenticator code.
 * Everything runs against Kratos' native flows through the gateway (see lib/kratos.ts).
 */
export function LoginPage() {
  const { mutate: login } = useLogin<{ token: string; email?: string }>();
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [flowId, setFlowId] = useState("");
  const [token, setToken] = useState("");
  const [enrolment, setEnrolment] = useState<Enrolment | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice] = useState<LoginNotice | null>(() => consumeLoginNotice());
  const config = getConfig();

  const finish = useCallback(
    (sessionToken: string, who: string) => {
      setStep("finishing");
      login(
        { token: sessionToken, email: who },
        {
          onSuccess: (result) => {
            if (result && result.success === false) {
              setError(result.error?.message ?? "Sign-in was refused.");
              setStep("email");
            }
          },
        },
      );
    },
    [login],
  );

  /** After the emailed code: is this account enrolled in an authenticator app yet? */
  const chooseSecondStep = useCallback(
    async (sessionToken: string, who: string) => {
      const who2 = await kratos.whoami(sessionToken);
      const address = who2.email ?? who;
      if (address !== who) setEmail(address);
      if (who2.state === "invalid") {
        clearSession();
        throw new KratosError(401, "rejected", ["The sign-in session is no longer valid. Start again."]);
      }
      if (who2.state === "aal2") {
        finish(sessionToken, address);
        return;
      }
      saveSession({ token: sessionToken, aal: "aal1", email: address });
      setToken(sessionToken);
      if (who2.state === "aal2_required") {
        setFlowId(await kratos.startSecondFactor(sessionToken));
        setStep("totp");
      } else {
        const started = await kratos.startEnrolment(sessionToken);
        const uri = otpauthUri(started.secret, address || "staff");
        setEnrolment({ ...started, uri, qr: await QRCode.toDataURL(uri, { margin: 1, width: 220 }) });
        setStep("enrol");
      }
    },
    [finish],
  );

  // Resume: an earlier step finished (aal1 session in this tab) but the authenticator step did not.
  useEffect(() => {
    const stored = getSession();
    if (stored && stored.aal === "aal1") {
      setBusy(true);
      setEmail(stored.email ?? "");
      chooseSecondStep(stored.token, stored.email ?? "")
        .catch((e) => setError(kratosErrorText(e)))
        .finally(() => setBusy(false));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      if (e instanceof KratosError && e.kind === "flow_expired") {
        setStep("email");
      }
      setError(kratosErrorText(e));
    } finally {
      setBusy(false);
    }
  };

  const sendCode = (values: { email: string }) =>
    run(async () => {
      const address = values.email.trim().toLowerCase();
      const id = await kratos.startLogin();
      await kratos.sendCode(id, address);
      setEmail(address);
      setFlowId(id);
      setStep("code");
    });

  const submitCode = (values: { code: string }) =>
    run(async () => {
      const sessionToken = await kratos.submitCode(flowId, email, values.code.trim());
      await chooseSecondStep(sessionToken, email);
    });

  const confirmEnrolment = (values: { code: string }) =>
    run(async () => {
      if (!enrolment) return;
      await kratos.confirmEnrolment(token, enrolment.flowId, values.code.trim());
      const who = await kratos.whoami(token);
      if (who.state === "aal2") {
        finish(token, who.email ?? email);
      } else {
        // enrolled, but the session still needs the second step: ask for the next code
        setFlowId(await kratos.startSecondFactor(token));
        setStep("totp");
        setError("The authenticator app is set up. Enter the next code it shows (the same code cannot be used twice).");
      }
    });

  const submitTotp = (values: { code: string }) =>
    run(async () => {
      try {
        const upgraded = await kratos.submitSecondFactor(token, flowId, values.code.trim());
        finish(upgraded, email);
      } catch (e) {
        if (e instanceof KratosError && e.kind === "rejected") {
          // a rejected code may have used up the flow: get a fresh one for the next try
          try {
            setFlowId(await kratos.startSecondFactor(token));
          } catch {
            // keep the old flow id; the next error will explain
          }
        }
        throw e;
      }
    });

  const startOver = () => {
    clearSession();
    setToken("");
    setEnrolment(null);
    setError(null);
    setStep("email");
  };

  return (
    <div style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", padding: 16 }}>
      <Card style={{ width: 460, maxWidth: "100%" }}>
        <Typography.Title level={3} style={{ marginTop: 0 }}>
          Adera Backoffice
        </Typography.Title>
        <Typography.Paragraph type="secondary">Staff sign-in</Typography.Paragraph>
        <Steps
          size="small"
          current={STEP_INDEX[step]}
          items={[{ title: "Email" }, { title: "Emailed code" }, { title: "Authenticator" }]}
          style={{ marginBottom: 16 }}
        />
        {notice ? <Alert style={{ marginBottom: 12 }} showIcon type={notice.kind} message={notice.text} /> : null}
        {error ? <Alert style={{ marginBottom: 12 }} showIcon type="error" message={error} /> : null}

        {step === "email" && (
          <Form layout="vertical" onFinish={sendCode} requiredMark={false}>
            <Form.Item
              label="Work email"
              name="email"
              rules={[{ required: true, message: "Enter your email address" }, { type: "email", message: "That does not look like an email address" }]}
            >
              <Input autoFocus autoComplete="username" inputMode="email" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy} block>
              Send me a code
            </Button>
          </Form>
        )}

        {step === "code" && (
          <Form layout="vertical" onFinish={submitCode} requiredMark={false}>
            <Typography.Paragraph>
              We sent a 6-digit code to <strong>{email}</strong>. It is valid for 5 minutes.
            </Typography.Paragraph>
            {config.devHints && (
              <Alert
                style={{ marginBottom: 12 }}
                type="info"
                message="Development stack"
                description={
                  <span>
                    No real email is sent here. Open the dev inbox{" "}
                    {config.mailpitUrl ? (
                      <a href={config.mailpitUrl} target="_blank" rel="noreferrer">
                        Mailpit ({config.mailpitUrl})
                      </a>
                    ) : (
                      "(Mailpit)"
                    )}{" "}
                    to read the code.
                  </span>
                }
              />
            )}
            <CodeInput />
            <Space>
              <Button type="primary" htmlType="submit" loading={busy}>
                Continue
              </Button>
              <Button onClick={startOver} disabled={busy}>
                Use another email
              </Button>
            </Space>
          </Form>
        )}

        {step === "enrol" && enrolment && (
          <Form layout="vertical" onFinish={confirmEnrolment} requiredMark={false}>
            <Typography.Paragraph>
              <strong>First time here: set up an authenticator app</strong> (Google Authenticator, Authy, 1Password, ...). Scan the QR code, or type the secret
              into the app, then enter the 6-digit code it shows.
            </Typography.Paragraph>
            <div style={{ textAlign: "center" }}>
              <img src={enrolment.qr} alt="QR code for the authenticator app" width={220} height={220} />
            </div>
            <Typography.Paragraph style={{ marginTop: 8 }}>
              Secret: <Typography.Text code copyable={{ text: enrolment.secret }}>{enrolment.secret}</Typography.Text>
            </Typography.Paragraph>
            <Typography.Paragraph style={{ wordBreak: "break-all" }}>
              Setup link: <Typography.Text code>{enrolment.uri}</Typography.Text> <CopyButton text={enrolment.uri} />
            </Typography.Paragraph>
            <Alert style={{ marginBottom: 12 }} type="warning" showIcon message="Keep the secret private. Anyone who has it can generate your codes." />
            <CodeInput label="Code from the authenticator app" />
            <Space>
              <Button type="primary" htmlType="submit" loading={busy}>
                Confirm and sign in
              </Button>
              <Button onClick={startOver} disabled={busy}>
                Start over
              </Button>
            </Space>
          </Form>
        )}

        {step === "totp" && (
          <Form layout="vertical" onFinish={submitTotp} requiredMark={false}>
            <Typography.Paragraph>
              Enter the 6-digit code from your authenticator app{email ? <> for <strong>{email}</strong></> : null}.
            </Typography.Paragraph>
            <CodeInput label="Authenticator code" />
            <Space>
              <Button type="primary" htmlType="submit" loading={busy}>
                Sign in
              </Button>
              <Button onClick={startOver} disabled={busy}>
                Use another account
              </Button>
            </Space>
          </Form>
        )}

        {step === "finishing" && <Typography.Paragraph>Signing you in...</Typography.Paragraph>}
        {busy && step === "email" && getSession()?.aal === "aal1" ? <Typography.Paragraph>Resuming sign-in...</Typography.Paragraph> : null}
      </Card>
    </div>
  );
}

function CodeInput({ label = "6-digit code" }: { label?: string }) {
  return (
    <Form.Item
      label={label}
      name="code"
      rules={[
        { required: true, message: "Enter the code" },
        { pattern: /^\d{6}$/, message: "The code is 6 digits" },
      ]}
    >
      <Input autoFocus inputMode="numeric" autoComplete="one-time-code" maxLength={6} style={{ letterSpacing: 4, fontSize: 18 }} />
    </Form.Item>
  );
}
