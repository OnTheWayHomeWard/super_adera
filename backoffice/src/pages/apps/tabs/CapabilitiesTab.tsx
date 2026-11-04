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
  purposeEn?: string;
  purposeAm?: string;
  purposeOm?: string;
}

const STATE_HELP: Record<string, string> = {
  GRANTED: "The app may use this. The user is asked at the moment of need and decides per capability.",
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
        What this app may ask for. The purpose is one plain sentence shown verbatim to users in the permission dialog (for example "Find a driver near you."), so it is reviewed here with the grant. It is required to grant the capabilities marked below; without one the dialog shows the default text. No links, at most 160 characters. Every change is on the audit tab.
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
          { title: "Purpose required", width: 110, render: (_, r) => (r.info.purposeRequired ? "yes (to grant)" : "no") },
          { title: "State", render: (_, r) => (r.row ? <StatusTag value={r.row.state} /> : <span style={{ opacity: 0.6 }}>not set</span>) },
          {
            title: "Purpose shown to users",
            render: (_, r) =>
              r.row?.purpose && Object.keys(r.row.purpose).length
                ? LANGS.filter((l) => r.row?.purpose?.[l]).map((l) => (
                    <div key={l}>
                      <b>{l}</b>: {r.row?.purpose?.[l]}
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
  const needPurpose = state === "GRANTED" && row.info.purposeRequired;

  const submit = async (values: FormValues) => {
    const purpose: I18n = {};
    if (values.purposeEn?.trim()) purpose.en = values.purposeEn.trim();
    if (values.purposeAm?.trim()) purpose.am = values.purposeAm.trim();
    if (values.purposeOm?.trim()) purpose.om = values.purposeOm.trim();
    setBusy(true);
    setError(null);
    try {
      await api(`apps/${appId}/capabilities/${encodeURIComponent(row.info.key)}`, { method: "PUT", body: { state: values.state, purpose } });
      await onSaved();
      onClose();
    } catch (e) {
      setError(e);
      applyApiError(form, e, { purpose: "purposeEn" });
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
          purposeEn: row.row?.purpose?.en ?? "",
          purposeAm: row.row?.purpose?.am ?? "",
          purposeOm: row.row?.purpose?.om ?? "",
        }}
      >
        <Form.Item label="State" name="state">
          <Radio.Group options={["GRANTED", "DENIED", "SUSPENDED"]} />
        </Form.Item>
        <Typography.Paragraph type="secondary">{STATE_HELP[state]}</Typography.Paragraph>
        {needPurpose && <Alert type="info" showIcon style={{ marginBottom: 12 }} message="A plain-language English sentence (at least 10 characters) is required, for example: Find a driver near you." />}
        <Form.Item
          label="Purpose - English"
          name="purposeEn"
          rules={needPurpose ? [{ required: true, min: 10, message: "At least 10 characters in English" }] : []}
        >
          <Input.TextArea rows={2} maxLength={160} showCount />
        </Form.Item>
        <Space style={{ width: "100%" }} direction="vertical">
          <Form.Item label="Purpose - Amharic (optional)" name="purposeAm" style={{ marginBottom: 8 }}>
            <Input.TextArea rows={2} maxLength={160} showCount />
          </Form.Item>
          <Form.Item label="Purpose - Afaan Oromoo (optional)" name="purposeOm" style={{ marginBottom: 0 }}>
            <Input.TextArea rows={2} maxLength={160} showCount />
          </Form.Item>
        </Space>
      </Form>
    </Modal>
  );
}
