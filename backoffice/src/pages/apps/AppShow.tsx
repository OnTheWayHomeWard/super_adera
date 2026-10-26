import { Alert, Avatar, Space, Spin, Tabs, Tag, Typography } from "antd";
import { Link, useParams, useSearchParams } from "react-router";
import { ConfirmButton, CopyButton, ErrorAlert, Mono, PageHeading, StatusTag, useCan } from "../../components/common";
import { api } from "../../lib/client";
import { i18nText } from "../../lib/format";
import { useApiQuery, useRefreshKeys } from "../../lib/hooks";
import type { AppDetail, AppStatus } from "../../lib/types";
import { AuditTab } from "./tabs/AuditTab";
import { CapabilitiesTab } from "./tabs/CapabilitiesTab";
import { EnvironmentsTab } from "./tabs/EnvironmentsTab";
import { GeneralTab } from "./tabs/GeneralTab";
import { KeysTab } from "./tabs/KeysTab";
import { LimitsTab } from "./tabs/LimitsTab";
import { NotificationLogTab } from "./tabs/NotificationLogTab";
import { PaymentTab } from "./tabs/PaymentTab";
import { ReportsTab, ReviewsTab } from "./tabs/ModerationTabs";
import { TemplatesTab } from "./tabs/TemplatesTab";
import { UsageTab } from "./tabs/UsageTab";

const STATUS_HELP: Record<AppStatus, string> = {
  DRAFT: "Not visible to users. The partner can call only in the DEV environment (a PROD deployment refuses it).",
  LIVE: "Visible to users. The partner can call in DEV and in PROD (each deployment accepts its own environment).",
  PAUSED: "Hidden from users and PROD calls are refused. DEV still works. Reversible.",
  KILLED: "Emergency stop: every call is refused. Only a super admin can bring it back.",
};

export function missingForLive(detail: AppDetail): string[] {
  const missing: string[] = [];
  if (!detail.app.logoImageId) missing.push("a logo");
  if (!detail.app.publisher.supportEmail) missing.push("a support email");
  return missing;
}

export function AppShowPage() {
  const { id = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const canWrite = useCan("write");
  const canRevive = useCan("app.revive");
  const refresh = useRefreshKeys();
  const detail = useApiQuery<AppDetail>(["app", id], `apps/${id}`);

  if (detail.isLoading) return <Spin />;
  if (detail.error || !detail.data) {
    return (
      <div>
        <ErrorAlert error={detail.error} title="Could not load the app" />
        <Link to="/apps">Back to the list</Link>
      </div>
    );
  }
  const d = detail.data;
  const app = d.app;
  const reload = () => refresh(["app", id], ["apps"], ["dashboard"]);
  const missing = missingForLive(d);
  const killedLocked = app.status === "KILLED" && !canRevive;

  const setStatus = async (status: AppStatus) => {
    await api(`apps/${id}/status`, { method: "POST", body: { status } });
    await reload();
  };

  const tab = params.get("tab") ?? "general";
  return (
    <div>
      <PageHeading
        title={
          <Space>
            <Avatar shape="square" size={40} src={app.logoUrl ?? undefined}>
              {app.slug[0]?.toUpperCase()}
            </Avatar>
            <span>{i18nText(app.name) || app.slug}</span>
            <StatusTag value={app.status} />
          </Space>
        }
        subtitle={
          <span>
            <Mono>{app.slug}</Mono> · id <Mono>{app.id}</Mono> <CopyButton text={app.id} />
          </span>
        }
        extra={<Link to="/apps">All apps</Link>}
      />

      <div style={{ marginBottom: 12 }}>
        <Typography.Text strong>Status: </Typography.Text>
        <Typography.Text>{STATUS_HELP[app.status]}</Typography.Text>
        {canWrite && (
          <div style={{ marginTop: 8 }}>
            <Space wrap>
              <span>Change to:</span>
              {(["DRAFT", "LIVE", "PAUSED", "KILLED"] as AppStatus[])
                .filter((s) => s !== app.status)
                .map((s) => (
                  <ConfirmButton
                    key={s}
                    label={s}
                    title={`Set ${i18nText(app.name) || app.slug} to ${s}?`}
                    okText={`Set ${s}`}
                    danger={s === "KILLED" || s === "PAUSED"}
                    disabled={killedLocked && s !== "KILLED"}
                    description={
                      <div>
                        <p>{STATUS_HELP[s]}</p>
                        {s === "LIVE" && missing.length > 0 && <Alert type="warning" showIcon message={`Not ready for LIVE yet: missing ${missing.join(" and ")}. The server will refuse.`} />}
                        {s === "KILLED" && <Alert type="error" showIcon message="This stops all of the partner's API calls immediately." />}
                      </div>
                    }
                    onConfirm={() => setStatus(s)}
                  />
                ))}
              {killedLocked && <Tag color="red">Only a super admin can revive a KILLED app</Tag>}
            </Space>
          </div>
        )}
        {app.status !== "LIVE" && missing.length > 0 && (
          <Alert style={{ marginTop: 8 }} type="info" showIcon message={`To go LIVE this app still needs ${missing.join(" and ")}.`} />
        )}
        {app.status !== "LIVE" && missing.length === 0 && app.status !== "KILLED" && (
          <Alert style={{ marginTop: 8 }} type="success" showIcon message="Ready for LIVE: it has a logo and a support email." />
        )}
      </div>

      <Tabs
        activeKey={tab}
        onChange={(key) => setParams({ tab: key }, { replace: true })}
        destroyOnHidden
        items={[
          { key: "general", label: "General", children: <GeneralTab detail={d} reload={reload} /> },
          { key: "capabilities", label: "Capabilities", children: <CapabilitiesTab detail={d} reload={reload} /> },
          { key: "limits", label: "Notification limits", children: <LimitsTab detail={d} reload={reload} /> },
          { key: "environments", label: "Environments and IPs", children: <EnvironmentsTab detail={d} reload={reload} /> },
          { key: "keys", label: "Partner keys", children: <KeysTab detail={d} reload={reload} /> },
          { key: "payment", label: "Payment", children: <PaymentTab detail={d} reload={reload} /> },
          { key: "templates", label: "Templates", children: <TemplatesTab appId={id} /> },
          { key: "log", label: "Notification log", children: <NotificationLogTab appId={id} /> },
          { key: "usage", label: "Usage", children: <UsageTab detail={d} /> },
          { key: "reviews", label: "Reviews", children: <ReviewsTab appId={id} /> },
          { key: "reports", label: "Reports", children: <ReportsTab appId={id} /> },
          { key: "audit", label: "Audit", children: <AuditTab appId={id} /> },
        ]}
      />
    </div>
  );
}
