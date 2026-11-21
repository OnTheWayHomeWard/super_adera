import { UploadOutlined } from "@ant-design/icons";
import { Alert, Button, Card, Descriptions, Image, Space, Spin, Typography, Upload } from "antd";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { ConfirmButton, ErrorAlert, Mono, PageHeading, RoleGate, Time, useCan } from "../../components/common";
import { BANNER_IMAGE_ACCEPT, bannerFileProblem, emptyBannerForm, toBannerForm, type BannerSettings, type BannerView } from "../../lib/banners";
import { api } from "../../lib/client";
import { i18nText } from "../../lib/format";
import { useApiQuery, useRefreshKeys } from "../../lib/hooks";
import type { AppView } from "../../lib/types";
import { BannerForm } from "./BannerForm";
import { StateBadge, StatusButtons } from "./BannerList";

async function uploadImage(id: string, file: File) {
  const form = new FormData();
  form.append("file", file);
  return api<BannerView>(`banners/${id}/image`, { method: "POST", form });
}

export function BannerCreatePage() {
  const navigate = useNavigate();
  const canWrite = useCan("write");
  const apps = useApiQuery<AppView[]>(["apps"], "apps");
  const settings = useApiQuery<BannerSettings>(["banner-settings"], "banners/settings");
  const [initial] = useState(() => emptyBannerForm());
  return (
    <div>
      <PageHeading title="New banner" subtitle="Starts as a draft. Add an image, then publish it." extra={<Link to="/banners">All banners</Link>} />
      {!canWrite && <Alert type="warning" showIcon message="Your role is read-only: you cannot create banners." style={{ marginBottom: 12 }} />}
      <ErrorAlert error={apps.error ?? settings.error} />
      <Card>
        <BannerForm
          initial={initial}
          apps={apps.data ?? []}
          settings={settings.data}
          submitLabel="Create banner"
          disabled={!canWrite}
          pickImage
          onCancel={() => navigate("/banners")}
          onSubmit={async (payload, image) => {
            const created = await api<BannerView>("banners", { method: "POST", body: payload });
            if (image) {
              try {
                await uploadImage(created.id, image);
              } catch {
                // the banner exists; the edit page shows it without an image and offers the upload again
              }
            }
            navigate(`/banners/${created.id}`);
          }}
        />
      </Card>
    </div>
  );
}

export function BannerEditPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const canWrite = useCan("write");
  const refresh = useRefreshKeys();
  const banner = useApiQuery<BannerView>(["banner", id], `banners/${id}`);
  const apps = useApiQuery<AppView[]>(["apps"], "apps");
  const settings = useApiQuery<BannerSettings>(["banner-settings"], "banners/settings");
  const [uploadError, setUploadError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false);

  if (banner.isLoading) return <Spin />;
  if (banner.error || !banner.data) {
    return (
      <div>
        <ErrorAlert error={banner.error} title="Could not load the banner" />
        <Link to="/banners">Back to the list</Link>
      </div>
    );
  }
  const b = banner.data;
  const reload = () => refresh(["banner", id], ["banners"]);

  const upload = async (file: File) => {
    setUploadError(null);
    const problem = bannerFileProblem(file, settings.data?.maxImageBytes);
    if (problem) {
      setUploadError(new Error(problem));
      return false;
    }
    setUploading(true);
    try {
      await uploadImage(b.id, file);
      await reload();
    } catch (e) {
      setUploadError(e);
    } finally {
      setUploading(false);
    }
    return false;
  };

  return (
    <div>
      <PageHeading
        title={
          <Space>
            <span>{i18nText(b.title)}</span>
            <StateBadge banner={b} />
          </Space>
        }
        subtitle={
          <span>
            Status <Mono>{b.status}</Mono> · id <Mono>{b.id}</Mono>
          </span>
        }
        extra={<Link to="/banners">All banners</Link>}
      />
      <Space style={{ marginBottom: 16 }} wrap>
        <StatusButtons banner={b} onChanged={reload} size="middle" />
        <RoleGate ability="write">
          <ConfirmButton
            label="Delete"
            size="middle"
            danger
            title="Delete this banner?"
            description="The banner and its image are deleted for good. Pausing it is the reversible alternative."
            onConfirm={async () => {
              await api(`banners/${b.id}`, { method: "DELETE" });
              await refresh(["banners"]);
              navigate("/banners");
            }}
          />
        </RoleGate>
      </Space>

      <Card style={{ marginBottom: 16 }}>
        <Typography.Title level={5}>Image</Typography.Title>
        <Space align="start" size="large" wrap>
          {b.images?.medium ? (
            <Image src={b.images.medium.url} width={320} alt="Banner image" preview={{ src: b.images.large?.url }} />
          ) : (
            <Alert type="warning" showIcon message="No image yet (needed to publish)." />
          )}
          <Space direction="vertical">
            <RoleGate ability="write">
              <Upload accept={BANNER_IMAGE_ACCEPT} showUploadList={false} beforeUpload={upload}>
                <Button icon={<UploadOutlined />} loading={uploading}>
                  {b.images ? "Replace image" : "Upload image"}
                </Button>
              </Upload>
              <Typography.Text type="secondary">PNG, JPEG, GIF or BMP. Stored as JPEG in three sizes.</Typography.Text>
            </RoleGate>
            {b.images ? (
              <Typography.Text type="secondary">
                {(["small", "medium", "large"] as const)
                  .map((s) => (b.images?.[s] ? `${s} ${b.images[s]!.width}×${b.images[s]!.height}` : null))
                  .filter(Boolean)
                  .join(" · ")}
              </Typography.Text>
            ) : null}
          </Space>
        </Space>
        <ErrorAlert error={uploadError} title="Image" />
        <Descriptions size="small" bordered column={{ xs: 1, md: 2 }} style={{ marginTop: 16 }}>
          <Descriptions.Item label="Created">
            <Time value={b.createdAt} />
          </Descriptions.Item>
          <Descriptions.Item label="Updated">
            <Time value={b.updatedAt} />
          </Descriptions.Item>
          <Descriptions.Item label="Shown to users now">{b.visible ? "yes" : "no"}</Descriptions.Item>
          <Descriptions.Item label="Linked app">{b.app ? <Link to={`/apps/${b.app.id}`}>{i18nText(b.app.name) || b.app.slug}</Link> : "-"}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card>
        <Typography.Title level={5}>{canWrite ? "Edit banner" : "Banner details (read only)"}</Typography.Title>
        <BannerForm
          key={b.updatedAt}
          initial={toBannerForm(b)}
          original={b}
          apps={apps.data ?? []}
          settings={settings.data}
          submitLabel="Save changes"
          disabled={!canWrite}
          imageUrl={b.images?.medium?.url ?? null}
          onSubmit={async (payload) => {
            await api(`banners/${b.id}`, { method: "PATCH", body: payload });
            await reload();
          }}
        />
      </Card>
    </div>
  );
}
