import { Alert, Button, Form, Input, Modal, Radio, Space, Table, Typography } from "antd";
import { useState } from "react";
import { applyApiError, ErrorAlert, RoleGate, StatusTag, Time } from "../../../components/common";
import { api } from "../../../lib/client";
import { useApiQuery } from "../../../lib/hooks";
import { LANGS, type CapabilityInfo, type CapabilityRow, type I18n } from "../../../lib/types";
import type { TabProps } from "./types";

interface Row {
  info: CapabilityInfo;
  row?: CapabilityRow;
}

interface FormValues {
  state: "GRANTED" | "DENIED" | "SUSPENDED";
  reasonEn?: string;
  reasonAm?: string;
  reasonOm?: string;
}

const STATE_HELP: Record<string, string> = {
  GRANTED: "The app may use this. The user still has to consent when the bridge is built.",
  DENIED: "The app asked or could ask for it and was refused.",
  SUSPENDED: "Was granted, now switched off (for example while investigating). Reversible by granting again.",
};

export function CapabilitiesTab({ detail, reload }: TabProps) {
  const catalog = useApiQuery<CapabilityInfo[]>(["capabilities"], "capabilities");
  const [editing, setEditing] = useState<Row | null>(null);

  const rows: Row[] = (catalog.data ?? []).map((info) => ({ info, row: detail.capabilities.find((c) => c.capability === info.key) }));

  return (
    <div>
      <Typography.Paragraph type="secondary">
        What this app may ask for. A reason is shown verbatim to users, so it must be plain language; it is required to grant the capabilities marked below.
        Granting a notification channel also creates default limits for it (see the limits tab).
      </Typography.Paragraph>
      <ErrorAlert error={catalog.error} />
      <Table<Row>
        size="small"
        rowKey={(r) => r.info.key}
        loading={catalog.isLoading}
        pagination={false}
        dataSource={rows}
        columns={[
          { title: "Capability", render: (_, r) => <code>{r.info.key}</code> },
          { title: "What it allows", render: (_, r) => r.info.description },
          { title: "Reason required", width: 110, render: (_, r) => (r.info.reasonRequired ? "yes (to grant)" : "no") },
          { title: "State", render: (_, r) => (r.row ? <StatusTag value={r.row.state} /> : <span style={{ opacity: 0.6 }}>not set</span>) },
          {
            title: "Reason shown to users",
            render: (_, r) =>
              r.row?.reason && Object.keys(r.row.reason).length
                ? LANGS.filter((l) => r.row?.reason?.[l]).map((l) => (
                    <div key={l}>
                      <b>{l}</b>: {r.row?.reason?.[l]}
                    </div>
                  ))
                : "-",
          },
          { title: "Updated", render: (_, r) => <Time value={r.row?.updatedAt} /> },
          {
            title: "",
            render: (_, r) => (
              <RoleGate ability="write">
                <Button size="small" onClick={() => setEditing(r)}>
                  Change
                </Button>
              </RoleGate>
            ),
          },
        ]}
      />
      {editing && <CapabilityModal appId={detail.app.id} row={editing} onClose={() => setEditing(null)} onSaved={reload} />}
    </div>
  );
}

function CapabilityModal({ appId, row, onClose, onSaved }: { appId: string; row: Row; onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [form] = Form.useForm<FormValues>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const state = Form.useWatch("state", form) ?? row.row?.state ?? "GRANTED";
  const needReason = state === "GRANTED" && row.info.reasonRequired;

  const submit = async (values: FormValues) => {
    const reason: I18n = {};
    if (values.reasonEn?.trim()) reason.en = values.reasonEn.trim();
    if (values.reasonAm?.trim()) reason.am = values.reasonAm.trim();
    if (values.reasonOm?.trim()) reason.om = values.reasonOm.trim();
    setBusy(true);
    setError(null);
    try {
      await api(`apps/${appId}/capabilities/${encodeURIComponent(row.info.key)}`, { method: "PUT", body: { state: values.state, reason } });
      await onSaved();
      onClose();
    } catch (e) {
      setError(e);
      applyApiError(form, e, { reason: "reasonEn" });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open title={`Capability ${row.info.key}`} onCancel={onClose} onOk={() => form.submit()} okText="Save" confirmLoading={busy} maskClosable={false} width={640}>
      <Typography.Paragraph>{row.info.description}</Typography.Paragraph>
      <ErrorAlert error={error} />
      <Form
        form={form}
        layout="vertical"
        onFinish={submit}
        initialValues={{
          state: row.row?.state ?? "GRANTED",
          reasonEn: row.row?.reason?.en ?? "",
          reasonAm: row.row?.reason?.am ?? "",
          reasonOm: row.row?.reason?.om ?? "",
        }}
      >
        <Form.Item label="State" name="state">
          <Radio.Group options={["GRANTED", "DENIED", "SUSPENDED"]} />
        </Form.Item>
        <Typography.Paragraph type="secondary">{STATE_HELP[state]}</Typography.Paragraph>
        {needReason && <Alert type="info" showIcon style={{ marginBottom: 12 }} message="A plain-language English reason (at least 10 characters) is required, for example: To show technicians near you." />}
        <Form.Item
          label="Reason - English"
          name="reasonEn"
          rules={needReason ? [{ required: true, min: 10, message: "At least 10 characters in English" }] : []}
        >
          <Input.TextArea rows={2} maxLength={300} />
        </Form.Item>
        <Space style={{ width: "100%" }} direction="vertical">
          <Form.Item label="Reason - Amharic (optional)" name="reasonAm" style={{ marginBottom: 8 }}>
            <Input.TextArea rows={2} maxLength={300} />
          </Form.Item>
          <Form.Item label="Reason - Afaan Oromoo (optional)" name="reasonOm" style={{ marginBottom: 0 }}>
            <Input.TextArea rows={2} maxLength={300} />
          </Form.Item>
        </Space>
      </Form>
    </Modal>
  );
}
