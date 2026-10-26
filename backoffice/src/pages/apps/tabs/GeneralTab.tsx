import { UploadOutlined } from "@ant-design/icons";
import { Alert, Button, Descriptions, Image, Space, Typography, Upload } from "antd";
import { useState } from "react";
import { ConfirmButton, ErrorAlert, Mono, RoleGate, Time, useCan } from "../../../components/common";
import { api } from "../../../lib/client";
import { i18nText } from "../../../lib/format";
import { LOGO_ACCEPT, LOGO_FORMATS_TEXT, logoFileProblem } from "../../../lib/logoFile";
import { AppForm } from "../AppForm";
import { toFormValues } from "../appForm";
import type { TabProps } from "./types";

export function GeneralTab({ detail, reload }: TabProps) {
  const app = detail.app;
  const canWrite = useCan("write");
  const [uploadError, setUploadError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false);

  const upload = async (file: File) => {
    setUploadError(null);
    const problem = logoFileProblem(file);
    if (problem) {
      setUploadError(new Error(problem));
      return false;
    }
    setUploading(true);
    try {
      const form = new FormData();
      form.append("file", file);
      await api(`apps/${app.id}/logo`, { method: "POST", form });
      await reload();
    } catch (e) {
      setUploadError(e);
    } finally {
      setUploading(false);
    }
    return false; // we upload ourselves
  };

  return (
    <div>
      <Typography.Title level={5}>Logo</Typography.Title>
      <Space align="start" size="large" wrap>
        {app.logoUrl ? <Image src={app.logoUrl} width={96} alt="App logo" /> : <Alert type="warning" showIcon message="No logo yet (needed to go LIVE)." />}
        <RoleGate ability="write">
          <Space direction="vertical">
            <Upload accept={LOGO_ACCEPT} showUploadList={false} beforeUpload={upload}>
              <Button icon={<UploadOutlined />} loading={uploading}>
                {app.logoUrl ? "Replace logo" : "Upload logo"}
              </Button>
            </Upload>
            <Typography.Text type="secondary">{LOGO_FORMATS_TEXT}, up to 2 MiB. It is resized on the server.</Typography.Text>
            {app.logoUrl && (
              <ConfirmButton
                label="Remove logo"
                title="Remove the logo?"
                danger
                description="An app without a logo cannot go LIVE."
                onConfirm={async () => {
                  await api(`apps/${app.id}/logo`, { method: "DELETE" });
                  await reload();
                }}
              />
            )}
          </Space>
        </RoleGate>
      </Space>
      <ErrorAlert error={uploadError} title="Logo" />

      <Descriptions size="small" bordered column={{ xs: 1, md: 2 }} style={{ margin: "16px 0" }}>
        <Descriptions.Item label="Rating">{app.ratingCount ? `${Number(app.ratingAvg).toFixed(2)} from ${app.ratingCount} reviews` : "no reviews"}</Descriptions.Item>
        <Descriptions.Item label="Created">
          <Time value={app.createdAt} />
        </Descriptions.Item>
        <Descriptions.Item label="Updated">
          <Time value={app.updatedAt} />
        </Descriptions.Item>
        <Descriptions.Item label="Display name">{i18nText(app.name)}</Descriptions.Item>
        <Descriptions.Item label="Logo image id">{app.logoImageId ? <Mono>{app.logoImageId}</Mono> : "-"}</Descriptions.Item>
      </Descriptions>

      <Typography.Title level={5}>{canWrite ? "Edit app" : "App details (read only)"}</Typography.Title>
      <AppForm
        key={app.updatedAt}
        initial={toFormValues(app)}
        original={app}
        submitLabel="Save changes"
        disabled={!canWrite}
        onSubmit={async (payload) => {
          await api(`apps/${app.id}`, { method: "PATCH", body: payload });
          await reload();
        }}
      />
    </div>
  );
}
