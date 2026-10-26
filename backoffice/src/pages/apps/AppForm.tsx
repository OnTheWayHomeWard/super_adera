import { App as AntApp, Button, Col, Form, Input, InputNumber, Row, Select, Space, Typography } from "antd";
import { useState } from "react";
import { applyApiError, ErrorAlert } from "../../components/common";
import { APP_FORM_FIELDS, buildAppPayload, ExtraJsonError, SERVER_TO_FORM, type AppFormValues } from "./appForm";
import type { AppView } from "../../lib/types";

const LANG_LABEL = { En: "English", Am: "Amharic (አማርኛ)", Om: "Afaan Oromoo" } as const;

interface Props {
  initial: AppFormValues;
  /** Present when editing: the slug is then fixed and only changed fields are sent. */
  original?: AppView;
  submitLabel: string;
  disabled?: boolean;
  onSubmit: (payload: Record<string, unknown>) => Promise<unknown>;
  onCancel?: () => void;
}

/** One form for creating and editing a mini app. Server validation messages are shown next to the fields. */
export function AppForm({ initial, original, submitLabel, disabled, onSubmit, onCancel }: Props) {
  const [form] = Form.useForm<AppFormValues>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const { message } = AntApp.useApp();

  const finish = async (values: AppFormValues) => {
    setError(null);
    setNotice(null);
    let payload: Record<string, unknown>;
    try {
      payload = buildAppPayload(values, original);
    } catch (e) {
      if (e instanceof ExtraJsonError) {
        form.setFields([{ name: "extraJson", errors: [`Extra data ${e.message}`] }]);
        return;
      }
      throw e;
    }
    if (original && Object.keys(payload).length === 0) {
      setNotice("Nothing was changed.");
      return;
    }
    setBusy(true);
    try {
      await onSubmit(payload);
      if (original) message.success(`Saved: ${Object.keys(payload).join(", ")}`);
    } catch (e) {
      setError(e);
      applyApiError(form, e, SERVER_TO_FORM);
    } finally {
      setBusy(false);
    }
  };

  const lang = (prefix: "name" | "desc", rows = 1) =>
    (["En", "Am", "Om"] as const).map((l) => (
      <Col xs={24} md={8} key={`${prefix}${l}`}>
        <Form.Item
          label={`${prefix === "name" ? "Name" : "Description"} - ${LANG_LABEL[l]}`}
          name={`${prefix}${l}`}
          rules={prefix === "name" && l === "En" ? [{ required: true, message: "An English name is required" }, { max: 100, message: "At most 100 characters" }] : [{ max: prefix === "name" ? 100 : 1000, message: `At most ${prefix === "name" ? 100 : 1000} characters` }]}
        >
          {rows > 1 ? <Input.TextArea rows={rows} maxLength={1000} /> : <Input maxLength={100} />}
        </Form.Item>
      </Col>
    ));

  return (
    <Form form={form} layout="vertical" initialValues={initial} onFinish={finish} disabled={disabled} scrollToFirstError>
      <ErrorAlert error={error} title="Could not save" />
      <Typography.Title level={5}>Identity</Typography.Title>
      <Row gutter={12}>
        <Col xs={24} md={8}>
          <Form.Item
            label="Slug"
            name="slug"
            extra={original ? "The slug is permanent." : "2-40 characters: lowercase letters, digits and dashes. Cannot be changed later."}
            rules={original ? [] : [{ required: true, message: "A slug is required" }, { pattern: /^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])$/, message: "2-40 chars: lowercase letters, digits, dashes (not at the ends)" }]}
          >
            <Input disabled={!!original} />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label="Priority" name="priority" extra="Lower numbers are listed first (0 to 100000)." rules={[{ required: true, message: "Enter a number" }]}>
            <InputNumber min={0} max={100000} precision={0} style={{ width: "100%" }} />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label="Brand colour" name="brandColor" extra="Like #0F9D58, or empty." rules={[{ pattern: /^$|^#[0-9A-Fa-f]{6}$/, message: "Must look like #0F9D58" }]}>
            <Input placeholder="#0F9D58" maxLength={7} />
          </Form.Item>
        </Col>
      </Row>
      <Row gutter={12}>{lang("name")}</Row>
      <Row gutter={12}>{lang("desc", 3)}</Row>
      <Row gutter={12}>
        <Col xs={24} md={12}>
          <Form.Item label="Categories" name="categories" extra="Lowercase words like home or travel. Press Enter after each.">
            <Select mode="tags" tokenSeparators={[","]} open={false} suffixIcon={null} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label="Markets" name="markets" rules={[{ required: true, message: "Pick at least one market" }]}>
            <Select mode="multiple" options={[{ value: "ET", label: "ET (Ethiopia)" }, { value: "US", label: "US" }]} />
          </Form.Item>
        </Col>
      </Row>

      <Typography.Title level={5}>Publisher and support</Typography.Title>
      <Row gutter={12}>
        <Col xs={24} md={12}>
          <Form.Item label="Publisher name" name="publisherName">
            <Input maxLength={150} />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label="Publisher legal name" name="publisherLegalName">
            <Input maxLength={200} />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label="Support email" name="supportEmail" extra="Required to go LIVE." rules={[{ type: "email", message: "Not a valid email address" }]}>
            <Input />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label="Support phone" name="supportPhone">
            <Input maxLength={30} />
          </Form.Item>
        </Col>
        <Col xs={24} md={8}>
          <Form.Item label="Website" name="website" extra="https:// only." rules={[{ pattern: /^$|^https:\/\/\S+$/, message: "Must start with https://" }]}>
            <Input />
          </Form.Item>
        </Col>
      </Row>

      <Typography.Title level={5}>Where the app runs</Typography.Title>
      <Row gutter={12}>
        <Col xs={24} md={12}>
          <Form.Item label="Entry URL" name="entryUrl" extra="The page opened inside the super app. https://, or http://localhost for development." rules={[{ pattern: /^$|^(https:\/\/|http:\/\/localhost|http:\/\/127\.0\.0\.1)\S*$/, message: "Must start with https://" }]}>
            <Input />
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label="Allowed origins" name="allowedOrigins" extra="Origins that may talk to the app bridge, like https://fix.adera.et (no path). Press Enter after each; at most 10.">
            <Select mode="tags" tokenSeparators={[",", " "]} open={false} suffixIcon={null} />
          </Form.Item>
        </Col>
      </Row>

      <Form.Item label="Extra data (JSON object)" name="extraJson" extra="Free-form settings. Removing a key here removes it from the app.">
        <Input.TextArea rows={5} spellCheck={false} style={{ fontFamily: "monospace" }} />
      </Form.Item>

      {notice ? <Typography.Paragraph type="success">{notice}</Typography.Paragraph> : null}
      <Space>
        <Button type="primary" htmlType="submit" loading={busy} disabled={disabled}>
          {submitLabel}
        </Button>
        {onCancel ? <Button onClick={onCancel}>Cancel</Button> : null}
      </Space>
    </Form>
  );
}

export const _fields = APP_FORM_FIELDS;
