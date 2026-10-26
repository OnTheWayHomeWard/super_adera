import { PlusOutlined, MinusCircleOutlined } from "@ant-design/icons";
import { Alert, Button, Checkbox, DatePicker, Drawer, Form, Input, InputNumber, Select, Space, Table, Tabs, Typography } from "antd";
import { useState } from "react";
import { applyApiError, ConfirmButton, ErrorAlert, Mono, RoleGate, StatusTag, Time } from "../../../components/common";
import { api } from "../../../lib/client";
import { useApiQuery } from "../../../lib/hooks";
import { LANGS, type Lang, type Template } from "../../../lib/types";
import { buildTemplatePayload, emptyTemplateForm, TEMPLATE_EDIT_NOTICE, toTemplateForm, undeclaredPlaceholders, type TemplateFormValues } from "../templateForm";

export function TemplatesTab({ appId }: { appId: string }) {
  const list = useApiQuery<Template[]>(["templates", appId], "notification/templates", { appId });
  const [editing, setEditing] = useState<Template | "new" | null>(null);

  const toggle = async (t: Template) => {
    await api(`notification/templates/${t.id}`, { method: "PATCH", body: { active: !t.active } });
    await list.refetch();
  };

  return (
    <div>
      <Typography.Paragraph type="secondary">
        Partners cannot send free text: every message uses a template written and approved here. Values the partner fills in must be declared as params and are
        length-limited and link-checked. Changing params or texts creates a new version; the code and kind never change.
      </Typography.Paragraph>
      <RoleGate ability="write">
        <Button type="primary" icon={<PlusOutlined />} style={{ marginBottom: 12 }} onClick={() => setEditing("new")}>
          New template
        </Button>
      </RoleGate>
      <ErrorAlert error={list.error} />
      <Table<Template>
        size="small"
        rowKey="id"
        loading={list.isLoading}
        dataSource={list.data ?? []}
        pagination={false}
        scroll={{ x: "max-content" }}
        columns={[
          { title: "Code", dataIndex: "code", render: (v) => <Mono>{v}</Mono> },
          { title: "Kind", dataIndex: "kind" },
          { title: "Category", dataIndex: "category" },
          { title: "Languages", render: (_, t) => Object.keys(t.content).join(", ") },
          { title: "Params", render: (_, t) => <span style={{ display: "inline-block", minWidth: 120 }}>{t.params.map((p) => p.name).join(", ") || "-"}</span> },
          { title: "Version", dataIndex: "version" },
          { title: "Active", dataIndex: "active", render: (v) => <StatusTag value={v ? "ACTIVE" : "DISABLED"} /> },
          { title: "Usable now", className: "nowrap", render: (_, t) => (t.usableNow ? "yes" : <span title="why this template cannot be used right now">{t.unusableReason}</span>) },
          { title: "Valid from", className: "nowrap", render: (_, t) => <Time value={t.validFrom} /> },
          { title: "Expires", className: "nowrap", render: (_, t) => <Time value={t.expiresAt} /> },
          { title: "Updated", className: "nowrap", render: (_, t) => <Time value={t.updatedAt} /> },
          {
            title: "",
            fixed: "right" as const,
            render: (_, t) => (
              <RoleGate ability="write">
                <Space>
                  <Button size="small" onClick={() => setEditing(t)}>
                    Edit
                  </Button>
                  {t.active ? (
                    <ConfirmButton
                      label="Deactivate"
                      danger
                      title={`Deactivate ${t.code}?`}
                      description="Partner sends using this template are refused (template_inactive) until it is activated again."
                      onConfirm={() => toggle(t)}
                    />
                  ) : (
                    <Button size="small" onClick={() => toggle(t)}>
                      Activate
                    </Button>
                  )}
                </Space>
              </RoleGate>
            ),
          },
        ]}
      />
      {editing && (
        <TemplateDrawer
          appId={appId}
          template={editing === "new" ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await list.refetch();
          }}
        />
      )}
    </div>
  );
}

const TEXT_FIELDS: { key: "title" | "body" | "sms" | "emailSubject" | "emailHtml"; label: string; rows: number; help: string }[] = [
  { key: "title", label: "Title", rows: 1, help: "Up to 100 characters. Required (in English) for IN_APP templates." },
  { key: "body", label: "Body", rows: 3, help: "Up to 1000 characters. The English body is required." },
  { key: "sms", label: "SMS text", rows: 2, help: "Up to 320 characters. Empty = the body is sent." },
  { key: "emailSubject", label: "Email subject", rows: 1, help: "Up to 150 characters. Empty = the title (or start of the body)." },
  { key: "emailHtml", label: "Email HTML", rows: 5, help: "Up to 20000 characters. Values are HTML-escaped when filled in." },
];

function TemplateDrawer({ appId, template, onClose, onSaved }: { appId: string; template?: Template; onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [form] = Form.useForm<TemplateFormValues>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState<string | null>(null);
  const kind = Form.useWatch("kind", form) ?? template?.kind ?? "DIRECT";
  const watched = Form.useWatch([], form) as TemplateFormValues | undefined;
  const missing = watched ? undeclaredPlaceholders(watched) : [];

  const submit = async (values: TemplateFormValues) => {
    setError(null);
    setInfo(null);
    const payload = buildTemplatePayload(values, appId, template);
    if (template && Object.keys(payload).length === 0) {
      setInfo("Nothing was changed.");
      return;
    }
    setBusy(true);
    try {
      if (template) await api(`notification/templates/${template.id}`, { method: "PATCH", body: payload });
      else await api("notification/templates", { method: "POST", body: payload });
      await onSaved();
    } catch (e) {
      setError(e);
      applyApiError(form, e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Drawer
      open
      width={820}
      title={template ? `Edit template ${template.code} (version ${template.version})` : "New template"}
      onClose={onClose}
      maskClosable={false}
      extra={
        <Space>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="primary" loading={busy} onClick={() => form.submit()}>
            {template ? "Save changes" : "Create template"}
          </Button>
        </Space>
      }
    >
      <ErrorAlert error={error} title="Could not save" />
      {template && <Alert type="warning" showIcon message="Changes also apply to messages already queued for delivery" description={TEMPLATE_EDIT_NOTICE} style={{ marginBottom: 12 }} />}
      {info && <Alert type="info" showIcon message={info} style={{ marginBottom: 12 }} />}
      {missing.length > 0 && <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={`Used in the texts but not declared as a param: ${missing.map((m) => `{${m}}`).join(", ")}`} />}
      <Form form={form} layout="vertical" initialValues={template ? toTemplateForm(template) : emptyTemplateForm} onFinish={submit} scrollToFirstError>
        <Space size="large" wrap align="start">
          <Form.Item
            label="Code"
            name="code"
            extra={template ? "Fixed after creation." : "2-60 chars: lowercase letters, digits, _ . - (e.g. job-accepted)."}
            rules={template ? [] : [{ required: true, message: "A code is required" }, { pattern: /^[a-z0-9][a-z0-9_.-]{0,58}[a-z0-9]$/, message: "2-60 chars of lowercase letters, digits, '_', '.', '-'" }]}
          >
            <Input disabled={!!template} style={{ width: 240 }} />
          </Form.Item>
          <Form.Item label="Kind" name="kind" extra={template ? "Fixed after creation." : "DIRECT = SMS or email. IN_APP = inbox + push."}>
            <Select disabled={!!template} style={{ width: 160 }} options={[{ value: "DIRECT" }, { value: "IN_APP" }]} />
          </Form.Item>
          <Form.Item label="Category" name="category" extra="PROMOTIONAL may be limited more strictly later.">
            <Select style={{ width: 180 }} options={["TRANSACTIONAL", "REMINDER", "PROMOTIONAL"].map((v) => ({ value: v }))} />
          </Form.Item>
          <Form.Item label="Active" name="active" valuePropName="checked">
            <Checkbox>switched on</Checkbox>
          </Form.Item>
        </Space>
        <Space size="large" wrap align="start">
          <Form.Item label="Valid from (optional)" name="validFrom">
            <DatePicker showTime />
          </Form.Item>
          <Form.Item label="Expires (optional)" name="expiresAt" extra="After this time sends are refused (template_expired). Must be in the future on create.">
            <DatePicker showTime />
          </Form.Item>
        </Space>

        <Typography.Title level={5}>Params (values the partner fills in)</Typography.Title>
        <Form.List name="params">
          {(fields, { add, remove }) => (
            <div>
              {fields.map((f) => (
                <Space key={f.key} align="baseline" wrap style={{ display: "flex" }}>
                  <Form.Item name={[f.name, "name"]} label={f.name === 0 ? "Name" : undefined} rules={[{ required: true, message: "Name" }, { pattern: /^[a-zA-Z][a-zA-Z0-9_]{0,29}$/, message: "Letters, digits, _ ; start with a letter" }]}>
                    <Input placeholder="name" style={{ width: 160 }} />
                  </Form.Item>
                  <Form.Item name={[f.name, "maxLength"]} label={f.name === 0 ? "Max length" : undefined} initialValue={100} rules={[{ required: true, message: "1-500" }]}>
                    <InputNumber min={1} max={500} precision={0} style={{ width: 100 }} />
                  </Form.Item>
                  <Form.Item name={[f.name, "required"]} valuePropName="checked" label={f.name === 0 ? "Required" : undefined} initialValue={true}>
                    <Checkbox />
                  </Form.Item>
                  <Form.Item name={[f.name, "allowLinks"]} valuePropName="checked" label={f.name === 0 ? "Allow links" : undefined} initialValue={false}>
                    <Checkbox />
                  </Form.Item>
                  <MinusCircleOutlined onClick={() => remove(f.name)} aria-label="Remove param" />
                </Space>
              ))}
              <Button icon={<PlusOutlined />} onClick={() => add({ name: "", maxLength: 100, required: true, allowLinks: false })} disabled={fields.length >= 10}>
                Add param
              </Button>
              <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
                Use a param in a text as {"{name}"}. At most 10. Links are blocked in values unless "Allow links" is ticked.
              </Typography.Paragraph>
            </div>
          )}
        </Form.List>

        <Typography.Title level={5} style={{ marginTop: 16 }}>
          Content per language
        </Typography.Title>
        <Typography.Paragraph type="secondary">
          Users get their own language; missing texts fall back to English. {kind === "IN_APP" ? "IN_APP needs an English title and body." : "DIRECT needs an English body."}
        </Typography.Paragraph>
        <Tabs
          items={LANGS.map((lang: Lang) => ({
            key: lang,
            forceRender: true,
            label: { en: "English", am: "Amharic", om: "Afaan Oromoo" }[lang],
            children: TEXT_FIELDS.map((tf) => (
              <Form.Item
                key={tf.key}
                label={tf.label}
                name={["content", lang, tf.key]}
                extra={tf.help}
                rules={lang === "en" && (tf.key === "body" || (tf.key === "title" && kind === "IN_APP")) ? [{ required: true, message: `English ${tf.label.toLowerCase()} is required` }] : []}
              >
                {tf.rows > 1 ? <Input.TextArea rows={tf.rows} spellCheck={false} /> : <Input />}
              </Form.Item>
            )),
          }))}
        />
      </Form>
    </Drawer>
  );
}
