import { UploadOutlined } from "@ant-design/icons";
import { App as AntApp, Button, Col, DatePicker, Form, Input, InputNumber, Radio, Row, Select, Space, Typography, Upload } from "antd";
import dayjs, { type Dayjs } from "dayjs";
import { useEffect, useMemo, useState } from "react";
import { applyApiError, ErrorAlert } from "../../components/common";
import {
  ACTION_LABEL,
  ACTION_TYPES,
  BANNER_IMAGE_ACCEPT,
  bannerFileProblem,
  buildBannerPayload,
  CATEGORIES,
  CATEGORY_LABEL,
  needsApp,
  PLACEMENT_LABEL,
  PLACEMENTS,
  SERVER_TO_FORM,
  TARGET_HELP,
  targetProblem,
  type BannerFormValues,
  type BannerSettings,
  type BannerView,
} from "../../lib/banners";
import { appLabel } from "../../lib/format";
import type { AppView } from "../../lib/types";
import { BannerPreview } from "./BannerPreview";

const LANG_LABEL = { En: "English", Am: "Amharic (አማርኛ)", Om: "Afaan Oromoo" } as const;

/** What the inputs hold: the times as dayjs for the pickers. */
type Fields = Omit<BannerFormValues, "startsAt" | "endsAt"> & { startsAt: Dayjs; endsAt: Dayjs | null };

interface Props {
  initial: BannerFormValues;
  /** Present when editing: only changed fields are sent. */
  original?: BannerView;
  apps: AppView[];
  settings?: BannerSettings;
  submitLabel: string;
  disabled?: boolean;
  /** Create only: a picture chosen before the banner exists is uploaded right after it is created. */
  pickImage?: boolean;
  onSubmit: (payload: Record<string, unknown>, image: File | null) => Promise<unknown>;
  onCancel?: () => void;
  /** Edit: the current picture, for the preview. */
  imageUrl?: string | null;
}

/** One form for creating and editing a banner, with a live preview of the chosen placement. */
export function BannerForm({ initial, original, apps, settings, submitLabel, disabled, pickImage, onSubmit, onCancel, imageUrl }: Props) {
  const [form] = Form.useForm<Fields>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [localUrl, setLocalUrl] = useState<string | null>(null);
  const { message } = AntApp.useApp();
  const domains = settings?.externalDomains ?? [];

  const initialFields: Fields = useMemo(() => ({ ...initial, startsAt: dayjs(initial.startsAt), endsAt: initial.endsAt ? dayjs(initial.endsAt) : null }), [initial]);
  const w = Form.useWatch([], form) as Partial<Fields> | undefined;
  const v = { ...initialFields, ...(w ?? {}) };
  const appNeeded = needsApp(v.category, v.actionType);
  const linkedApp = apps.find((a) => a.id === v.appId) ?? null;

  useEffect(() => () => {
    if (localUrl) URL.revokeObjectURL(localUrl);
  }, [localUrl]);

  const choose = (f: File) => {
    const problem = bannerFileProblem(f, settings?.maxImageBytes);
    setFileError(problem);
    if (!problem) {
      setFile(f);
      setLocalUrl(URL.createObjectURL(f));
    }
    return false;
  };

  const finish = async (fields: Fields) => {
    setError(null);
    setNotice(null);
    const values: BannerFormValues = { ...fields, target: fields.target ?? "", appId: fields.appId ?? null, startsAt: fields.startsAt.toISOString(), endsAt: fields.endsAt ? fields.endsAt.toISOString() : null };
    const payload = buildBannerPayload(values, original);
    if (original && Object.keys(payload).length === 0) {
      setNotice("Nothing was changed.");
      return;
    }
    setBusy(true);
    try {
      await onSubmit(payload, file);
      if (original) message.success(`Saved: ${Object.keys(payload).join(", ")}`);
    } catch (e) {
      setError(e);
      applyApiError(form, e, SERVER_TO_FORM);
    } finally {
      setBusy(false);
    }
  };

  const lang = (prefix: "title" | "sub") =>
    (["En", "Am", "Om"] as const).map((l) => {
      const max = prefix === "title" ? 80 : 160;
      return (
        <Col xs={24} md={8} key={`${prefix}${l}`}>
          <Form.Item
            label={`${prefix === "title" ? "Title" : "Subtitle"} - ${LANG_LABEL[l]}`}
            name={`${prefix}${l}`}
            rules={prefix === "title" && l === "En" ? [{ required: true, whitespace: true, message: "An English title is required" }] : []}
          >
            <Input maxLength={max} showCount />
          </Form.Item>
        </Col>
      );
    });

  return (
    <Row gutter={24}>
      <Col xs={24} xl={15}>
        <Form form={form} layout="vertical" initialValues={initialFields} onFinish={finish} disabled={disabled} scrollToFirstError>
          <ErrorAlert error={error} title="Could not save" />
          <Typography.Title level={5}>Text</Typography.Title>
          <Row gutter={12}>{lang("title")}</Row>
          <Row gutter={12}>{lang("sub")}</Row>

          <Typography.Title level={5}>Where and what</Typography.Title>
          <Row gutter={12}>
            <Col xs={24} md={12}>
              <Form.Item label="Placement" name="placement" rules={[{ required: true }]}>
                <Select options={PLACEMENTS.map((p) => ({ value: p, label: PLACEMENT_LABEL[p] }))} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="Category" name="category" rules={[{ required: true }]}>
                <Select options={CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))} />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title level={5}>On tap</Typography.Title>
          <Form.Item name="actionType" rules={[{ required: true }]}>
            <Radio.Group optionType="button" options={ACTION_TYPES.map((a) => ({ value: a, label: ACTION_LABEL[a] }))} />
          </Form.Item>
          <Row gutter={12}>
            <Col xs={24} md={12}>
              <Form.Item
                label={appNeeded ? "Linked mini app" : "Linked mini app (optional)"}
                name="appId"
                extra={linkedApp && linkedApp.status !== "LIVE" ? `This app is ${linkedApp.status}: the banner is not shown until the app is LIVE.` : "While the app is not LIVE, the banner is not shown."}
                rules={[{ required: appNeeded, message: "Pick the app: a featured app or an 'open a mini app' banner needs one" }]}
              >
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  placeholder="Pick an app"
                  options={apps.map((a) => ({ value: a.id, label: `${appLabel(a, a.id)}${a.status === "LIVE" ? "" : ` · ${a.status}`}` }))}
                />
              </Form.Item>
            </Col>
            {v.actionType !== "NONE" && (
              <Col xs={24} md={12}>
                <Form.Item
                  label={v.actionType === "MINI_APP" ? "Page in the app" : v.actionType === "INTERNAL" ? "Super-app link" : "Web address"}
                  name="target"
                  dependencies={["actionType"]}
                  extra={v.actionType === "EXTERNAL" ? `${TARGET_HELP.EXTERNAL} Allowed: ${domains.length ? domains.join(", ") : "none (switched off)"}.` : TARGET_HELP[v.actionType ?? "NONE"]}
                  rules={[
                    ({ getFieldValue }) => ({
                      validator: async (_, value: string) => {
                        const problem = targetProblem(getFieldValue("actionType"), value, domains);
                        if (problem) throw new Error(problem);
                      },
                    }),
                  ]}
                >
                  <Input placeholder={v.actionType === "MINI_APP" ? "/promo/first-ride" : v.actionType === "INTERNAL" ? "adera://wallet" : "https://"} maxLength={500} />
                </Form.Item>
              </Col>
            )}
          </Row>

          <Typography.Title level={5}>When and order</Typography.Title>
          <Row gutter={12}>
            <Col xs={24} md={8}>
              <Form.Item label="Starts" name="startsAt" rules={[{ required: true, message: "Pick the start" }]} extra="Your local time.">
                <DatePicker showTime={{ format: "HH:mm" }} format="YYYY-MM-DD HH:mm" style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                label="Ends (optional)"
                name="endsAt"
                dependencies={["startsAt"]}
                extra="Empty = no end."
                rules={[
                  ({ getFieldValue }) => ({
                    validator: async (_, value: Dayjs | null) => {
                      const start = getFieldValue("startsAt") as Dayjs | undefined;
                      if (value && start && !value.isAfter(start)) throw new Error("Must be after the start");
                    },
                  }),
                ]}
              >
                <DatePicker showTime={{ format: "HH:mm" }} format="YYYY-MM-DD HH:mm" style={{ width: "100%" }} allowClear />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item label="Priority" name="priority" extra="Higher is shown first (0 to 100000)." rules={[{ required: true, message: "Enter a number" }]}>
                <InputNumber min={0} max={100000} precision={0} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
          </Row>

          {pickImage && (
            <Form.Item label="Image" extra="PNG, JPEG, GIF or BMP, at least 1600 px wide for sharp pictures. Resized on the server. It can also be added later; a banner needs one to be published.">
              <Space direction="vertical">
                <Upload accept={BANNER_IMAGE_ACCEPT} showUploadList={false} beforeUpload={choose}>
                  <Button icon={<UploadOutlined />}>{file ? "Choose another image" : "Choose image"}</Button>
                </Upload>
                {file ? <Typography.Text>{file.name}</Typography.Text> : null}
                {fileError ? <Typography.Text type="danger">{fileError}</Typography.Text> : null}
              </Space>
            </Form.Item>
          )}

          {notice ? <Typography.Paragraph type="success">{notice}</Typography.Paragraph> : null}
          <Space>
            <Button type="primary" htmlType="submit" loading={busy} disabled={disabled}>
              {submitLabel}
            </Button>
            {onCancel ? <Button onClick={onCancel}>Cancel</Button> : null}
          </Space>
        </Form>
      </Col>
      <Col xs={24} xl={9}>
        <Typography.Title level={5}>Preview</Typography.Title>
        <BannerPreview
          placement={v.placement ?? "HOME_HERO"}
          category={v.category ?? "PROMOTION"}
          title={{ en: v.titleEn, am: v.titleAm, om: v.titleOm }}
          subtitle={{ en: v.subEn, am: v.subAm, om: v.subOm }}
          imageUrl={localUrl ?? imageUrl ?? null}
          app={linkedApp}
        />
      </Col>
    </Row>
  );
}
