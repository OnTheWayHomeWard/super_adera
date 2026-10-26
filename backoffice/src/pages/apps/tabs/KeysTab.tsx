import { DownloadOutlined } from "@ant-design/icons";
import { Alert, Button, Checkbox, DatePicker, Form, Input, Modal, Radio, Space, Table, Typography } from "antd";
import type { Dayjs } from "dayjs";
import { useState } from "react";
import { applyApiError, ConfirmButton, CopyButton, EnvTag, ErrorAlert, Mono, RoleGate, StatusTag, Time } from "../../../components/common";
import { api } from "../../../lib/client";
import { download } from "../../../lib/format";
import { useDeploymentEnvironment } from "../../../lib/hooks";
import { ENVIRONMENTS, type Environment, type KeyView } from "../../../lib/types";
import type { TabProps } from "./types";

interface Generated {
  key: KeyView;
  privateKeyPem: string;
  warning: string;
}

/** Shown when a key is made for the environment this deployment does not run: allowed, but it cannot be used here. */
function OtherEnvironmentNotice({ env, active }: { env: Environment; active?: Environment }) {
  if (!active || env === active) return null;
  return (
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 12 }}
      message={`This deployment runs ${active}, not ${env}.`}
      description={`A ${env} key is stored but not active here: this deployment refuses it with wrong_environment. It works on the ${env} deployment.`}
    />
  );
}

export function KeysTab({ detail, reload }: TabProps) {
  const appId = detail.app.id;
  const active = useDeploymentEnvironment();
  const [registering, setRegistering] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [generated, setGenerated] = useState<Generated | null>(null);
  const [revokeError, setRevokeError] = useState<unknown>(null);

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="How partners prove who they are"
        description="Partners sign every request with a private key only they hold; we store the matching public key. Preferred: the partner generates a key pair and you register the PUBLIC key. For DEV work you can generate a pair here: the private key is shown once and we cannot show it again. Up to 5 active keys per environment (DEV and PROD are separate). Only keys of the environment this deployment runs are accepted here."
      />
      <RoleGate ability="write">
        <Space style={{ marginBottom: 12 }}>
          <Button type="primary" onClick={() => setRegistering(true)}>
            Register a public key
          </Button>
          <Button onClick={() => setGenerating(true)}>Generate a key pair</Button>
        </Space>
      </RoleGate>
      <ErrorAlert error={revokeError} title="Revoke" />
      <Table<KeyView>
        size="small"
        rowKey="keyId"
        pagination={false}
        dataSource={detail.keys}
        scroll={{ x: "max-content" }}
        locale={{ emptyText: "No keys yet: the partner cannot call the API." }}
        columns={[
          { title: "Key id", dataIndex: "keyId", render: (v) => <Mono>{v}</Mono> },
          { title: "Environment", dataIndex: "environment", render: (v: string) => <EnvTag value={v} active={active === undefined ? undefined : v === active} /> },
          { title: "Algorithm", dataIndex: "algorithm" },
          { title: "Fingerprint", dataIndex: "fingerprint", render: (v: string) => <Typography.Text code copyable={{ text: v }} title={v} style={{ fontSize: 12 }}>{v.slice(0, 16)}…</Typography.Text> },
          { title: "Label", dataIndex: "label", render: (v) => v ?? "-" },
          { title: "Status", dataIndex: "status", render: (v) => <StatusTag value={v} /> },
          { title: "Expires", dataIndex: "expiresAt", className: "nowrap", render: (v) => <Time value={v} /> },
          { title: "Created", dataIndex: "createdAt", className: "nowrap", render: (v) => <Time value={v} /> },
          { title: "Revoked", dataIndex: "revokedAt", className: "nowrap", render: (v) => <Time value={v} /> },
          {
            title: "",
            fixed: "right" as const,
            render: (_, k) =>
              k.status === "ACTIVE" ? (
                <RoleGate ability="write">
                  <ConfirmButton
                    label="Revoke"
                    danger
                    title={`Revoke key ${k.keyId}?`}
                    description="The partner's requests signed with this key are refused immediately. This cannot be undone."
                    onConfirm={async () => {
                      setRevokeError(null);
                      try {
                        await api(`apps/${appId}/keys/${encodeURIComponent(k.keyId)}`, { method: "DELETE" });
                        await reload();
                      } catch (e) {
                        setRevokeError(e);
                        throw e;
                      }
                    }}
                  />
                </RoleGate>
              ) : null,
          },
        ]}
      />
      {registering && <RegisterModal appId={appId} active={active} onClose={() => setRegistering(false)} onSaved={reload} />}
      {generating && <GenerateModal appId={appId} active={active} onClose={() => setGenerating(false)} onGenerated={async (g) => { setGenerating(false); setGenerated(g); await reload(); }} />}
      {generated && <PrivateKeyModal appId={appId} generated={generated} onClose={() => setGenerated(null)} />}
    </div>
  );
}

interface RegisterValues {
  environment: Environment;
  label?: string;
  expiresAt?: Dayjs | null;
  publicKeyPem: string;
}

function RegisterModal({ appId, active, onClose, onSaved }: { appId: string; active?: Environment; onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [form] = Form.useForm<RegisterValues>();
  const env = Form.useWatch("environment", form) ?? active ?? "DEV";
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (v: RegisterValues) => {
    setBusy(true);
    setError(null);
    try {
      const body: Record<string, unknown> = { environment: v.environment, publicKeyPem: v.publicKeyPem.trim() };
      if (v.label?.trim()) body.label = v.label.trim();
      if (v.expiresAt) body.expiresAt = v.expiresAt.toDate().toISOString();
      await api(`apps/${appId}/keys`, { method: "POST", body });
      await onSaved();
      onClose();
    } catch (e) {
      setError(e);
      applyApiError(form, e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open title="Register a public key" onCancel={onClose} onOk={() => form.submit()} okText="Register" confirmLoading={busy} maskClosable={false} width={640}>
      <ErrorAlert error={error} />
      <Form form={form} layout="vertical" onFinish={submit} initialValues={{ environment: active ?? "DEV" }}>
        <Form.Item label="Environment" name="environment">
          <Radio.Group options={ENVIRONMENTS} />
        </Form.Item>
        <OtherEnvironmentNotice env={env} active={active} />
        <Form.Item label="Label (optional)" name="label" extra="For humans, like 'partner backend 2026'.">
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item label="Expires (optional)" name="expiresAt">
          <DatePicker showTime />
        </Form.Item>
        <Form.Item
          label="Public key (PEM)"
          name="publicKeyPem"
          extra="Paste the whole block including the BEGIN PUBLIC KEY and END PUBLIC KEY lines. RSA (2048 bits or more)."
          rules={[{ required: true, message: "Paste the public key" }, { pattern: /-----BEGIN PUBLIC KEY-----/, message: "That does not look like a PEM public key (BEGIN PUBLIC KEY)" }]}
        >
          <Input.TextArea rows={8} spellCheck={false} style={{ fontFamily: "monospace" }} placeholder={"-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"} />
        </Form.Item>
        <Alert type="warning" showIcon message="Never paste a PRIVATE key here. Only the public half is registered." />
      </Form>
    </Modal>
  );
}

function GenerateModal({ appId, active, onClose, onGenerated }: { appId: string; active?: Environment; onClose: () => void; onGenerated: (g: Generated) => Promise<void> }) {
  const [form] = Form.useForm<{ environment: Environment; label?: string; expiresAt?: Dayjs | null }>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const env = Form.useWatch("environment", form) ?? active ?? "DEV";
  const submit = async (v: { environment: Environment; label?: string; expiresAt?: Dayjs | null }) => {
    setBusy(true);
    setError(null);
    try {
      const body: Record<string, unknown> = { environment: v.environment };
      if (v.label?.trim()) body.label = v.label.trim();
      if (v.expiresAt) body.expiresAt = v.expiresAt.toDate().toISOString();
      await onGenerated(await api<Generated>(`apps/${appId}/keys/generate`, { method: "POST", body }));
    } catch (e) {
      setError(e);
      applyApiError(form, e);
      setBusy(false);
    }
  };
  return (
    <Modal open title="Generate a key pair" onCancel={onClose} onOk={() => form.submit()} okText="Generate" confirmLoading={busy} maskClosable={false}>
      <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="The private key is shown once, right after this step. We do not store it and cannot show it again." />
      <ErrorAlert error={error} />
      <Form form={form} layout="vertical" onFinish={submit} initialValues={{ environment: active ?? "DEV" }}>
        <Form.Item label="Environment" name="environment">
          <Radio.Group options={ENVIRONMENTS} />
        </Form.Item>
        <OtherEnvironmentNotice env={env} active={active} />
        {env === "PROD" && <Alert type="error" showIcon style={{ marginBottom: 12 }} message="For PROD the partner should generate their own key and give you only the public half. A private key that passes through here is exposed to whoever is looking at this screen." />}
        <Form.Item label="Label (optional)" name="label">
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item label="Expires (optional)" name="expiresAt">
          <DatePicker showTime />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function PrivateKeyModal({ appId, generated, onClose }: { appId: string; generated: Generated; onClose: () => void }) {
  const [saved, setSaved] = useState(false);
  const filename = `adera-${appId.slice(0, 8)}-${generated.key.environment.toLowerCase()}-${generated.key.keyId}.pem`;
  return (
    <Modal
      open
      width={720}
      title="Private key: shown once"
      closable={false}
      maskClosable={false}
      keyboard={false}
      onCancel={() => undefined}
      footer={
        <Button type="primary" disabled={!saved} onClick={onClose}>
          Close
        </Button>
      }
    >
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 12 }}
        message="Copy or download it now."
        description={`${generated.warning} Closing this window deletes it from the screen for good. Send it to the partner over a secure channel, never by plain email or chat.`}
      />
      <Typography.Paragraph>
        Key id <Mono>{generated.key.keyId}</Mono> ({generated.key.environment}), fingerprint <Mono>{generated.key.fingerprint}</Mono>
      </Typography.Paragraph>
      <pre style={{ maxHeight: 260, overflow: "auto", padding: 8, background: "rgba(128,128,128,0.12)", userSelect: "all", fontSize: 12 }}>{generated.privateKeyPem}</pre>
      <Space style={{ marginBottom: 12 }}>
        <CopyButton text={generated.privateKeyPem} label="Copy private key" />
        <Button size="small" icon={<DownloadOutlined />} onClick={() => download(filename, generated.privateKeyPem)}>
          Download .pem
        </Button>
      </Space>
      <div>
        <Checkbox checked={saved} onChange={(e) => setSaved(e.target.checked)}>
          I have saved the private key somewhere safe.
        </Checkbox>
      </div>
    </Modal>
  );
}
