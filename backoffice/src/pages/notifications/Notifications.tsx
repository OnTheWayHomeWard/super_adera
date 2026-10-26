import { Card, Space, Statistic } from "antd";
import { NotificationTable } from "../../components/NotificationTable";
import { ErrorAlert, PageHeading } from "../../components/common";
import { i18nText } from "../../lib/format";
import { useApiQuery } from "../../lib/hooks";
import type { AppView, NotificationStats } from "../../lib/types";

export function NotificationsPage() {
  const stats = useApiQuery<NotificationStats>(["notification-stats"], "notification/stats");
  const apps = useApiQuery<AppView[]>(["apps"], "apps");
  const names = Object.fromEntries((apps.data ?? []).map((a) => [a.id, i18nText(a.name) || a.slug]));
  return (
    <div>
      <PageHeading title="Notifications" subtitle="Delivery log across all mini apps. Message values and recipients are never shown." />
      <ErrorAlert error={stats.error} />
      {stats.data && (
        <Card size="small" style={{ marginBottom: 12 }}>
          <Space size="large" wrap>
            <Statistic title="Total" value={stats.data.total} />
            <Statistic title="Last 24 hours" value={stats.data.last24h.total} />
            {Object.entries(stats.data.byStatus).map(([k, v]) => (
              <Statistic key={k} title={k} value={v} />
            ))}
            {Object.entries(stats.data.byChannel).map(([k, v]) => (
              <Statistic key={k} title={`via ${k}`} value={v} />
            ))}
          </Space>
        </Card>
      )}
      <NotificationTable appNames={names} />
    </div>
  );
}
