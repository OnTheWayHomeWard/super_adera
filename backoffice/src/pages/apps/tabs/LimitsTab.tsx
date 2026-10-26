import { Alert, Button, Form, InputNumber, Modal, Radio, Table, Typography } from "antd";
import { useState } from "react";
import { applyApiError, ErrorAlert, RoleGate } from "../../../components/common";
import { api } from "../../../lib/client";
import { limitText } from "../../../lib/format";
import { CHANNELS, type Channel, type LimitRow } from "../../../lib/types";
import type { TabProps } from "./types";

const FIELDS = [
  { key: "maxPerUserPerDay", label: "Max per user per day", help: "Messages to one user in one UTC day." },
  { key: "maxPerDay", label: "Max per day", help: "Messages from this app in one UTC day." },
  { key: "maxDistinctUsersPerDay", label: "Max distinct users per day", help: "Different users reached in one UTC day." },
  { key: "maxTotal", label: "Max total", help: "Lifetime messages from this app on this channel." },
] as const;

type FieldKey = (typeof FIELDS)[number]["key"];

export function LimitsTab({ detail, reload }: TabProps) {
  const [editing, setEditing] = useState<Channel | null>(null);
  const byChannel = new Map<Channel, LimitRow>(detail.notificationLimits.map((l) => [l.channel, l]));
  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="How limits work"
        description={
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>
              <b>Empty / unlimited</b> (null): no cap on that measure.
            </li>
            <li>
              <b>0</b>: nothing is allowed (every send on that channel is refused with quota_exceeded).
            </li>
            <li>Days are UTC days. Limits apply per channel (SMS costs money: keep it tight).</li>
          </ul>
        }
      />
      <Table<Channel>
        size="small"
        rowKey={(c) => c}
        pagination={false}
        dataSource={CHANNELS}
        columns={[
          { title: "Channel", render: (_, c) => <b>{c}</b> },
          ...FIELDS.map((f) => ({
            title: f.label,
            render: (_: unknown, c: Channel) => (byChannel.has(c) ? limitText(byChannel.get(c)?.[f.key]) : "-"),
          })),
          { title: "Configured", render: (_, c) => (byChannel.has(c) ? "yes" : "no limits set") },
          {
            title: "",
            render: (_, c) => (
              <RoleGate ability="write">
                <Button size="small" onClick={() => setEditing(c)}>
                  Edit
                </Button>
              </RoleGate>
            ),
          },
        ]}
      />
      {editing && <LimitModal appId={detail.app.id} channel={editing} current={byChannel.get(editing)} onClose={() => setEditing(null)} onSaved={reload} />}
    </div>
  );
}

type FormValues = Record<`${FieldKey}Mode`, "unlimited" | "limit"> & Partial<Record<FieldKey, number | null>>;

function LimitModal({ appId, channel, current, onClose, onSaved }: { appId: string; channel: Channel; current?: LimitRow; onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [form] = Form.useForm<FormValues>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const initial: Record<string, unknown> = {};
  for (const f of FIELDS) {
    const v = current?.[f.key];
    initial[`${f.key}Mode`] = current && v === null ? "unlimited" : v === undefined ? "unlimited" : "limit";
    initial[f.key] = v ?? undefined;
  }

  const submit = async (values: FormValues) => {
    const body: Record<string, number | null> = {};
    for (const f of FIELDS) {
      body[f.key] = values[`${f.key}Mode`] === "unlimited" ? null : (values[f.key] ?? 0);
    }
    setBusy(true);
    setError(null);
    try {
      await api(`apps/${appId}/notification-limits/${channel}`, { method: "PUT", body });
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
    <Modal open title={`Limits for ${channel}`} onCancel={onClose} onOk={() => form.submit()} okText="Save limits" confirmLoading={busy} maskClosable={false} width={560}>
      <ErrorAlert error={error} />
      <Form form={form} layout="vertical" initialValues={initial} onFinish={submit}>
        {FIELDS.map((f) => (
          <Form.Item key={f.key} label={f.label} extra={f.help} style={{ marginBottom: 12 }}>
            <Form.Item name={`${f.key}Mode`} noStyle>
              <Radio.Group>
                <Radio value="unlimited">Unlimited</Radio>
                <Radio value="limit">Limit to</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item noStyle shouldUpdate={(a, b) => a[`${f.key}Mode`] !== b[`${f.key}Mode`]}>
              {({ getFieldValue }) =>
                getFieldValue(`${f.key}Mode`) === "limit" ? (
                  <Form.Item name={f.key} noStyle rules={[{ required: true, message: "Enter a whole number (0 = none)" }]}>
                    <InputNumber min={0} precision={0} style={{ width: 160 }} />
                  </Form.Item>
                ) : null
              }
            </Form.Item>
          </Form.Item>
        ))}
        <Typography.Text type="secondary">0 means nothing is allowed; choose Unlimited for no cap.</Typography.Text>
      </Form>
    </Modal>
  );
}
