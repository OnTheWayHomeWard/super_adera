import { Alert, Card, Col, Row, Space, Spin, Statistic, Table, Typography } from "antd";
import { Link } from "react-router";
import { ErrorAlert, PageHeading, StatusTag, Time, useCan } from "../components/common";
import { useApiList, useApiQuery } from "../lib/hooks";
import { i18nText, shortId } from "../lib/format";
import type { AuditEvent, DashboardData, NotificationRow, NotificationStats, Report, UserStats } from "../lib/types";

function Panel<T>({ title, query, children, extra }: { title: string; query: { isLoading: boolean; error: unknown; data: T | undefined }; children: (data: T) => React.ReactNode; extra?: React.ReactNode }) {
  return (
    <Card size="small" title={title} extra={extra} style={{ height: "100%" }}>
      {query.isLoading ? <Spin /> : query.error ? <ErrorAlert error={query.error} /> : query.data !== undefined ? children(query.data) : null}
    </Card>
  );
}

/** Home: real counts from the services, nothing estimated. Each panel loads (and fails) on its own. */
export function DashboardPage() {
  const canAudit = useCan("audit.read");
  const overview = useApiQuery<DashboardData>(["dashboard"], "dashboard");
  const notifStats = useApiQuery<NotificationStats>(["notification-stats"], "notification/stats");
  const userStats = useApiQuery<UserStats>(["user-stats"], "users/stats");
  const recentNotifications = useApiList<NotificationRow>(["recent-notifications"], "notification/notifications", { limit: 8 });
  const openReports = useApiList<Report>(["open-reports"], "reports", { status: "OPEN", size: 8 });
  const audit = useApiList<AuditEvent>(["recent-audit"], "audit", { limit: 8 }, { enabled: canAudit });

  return (
    <div>
      <PageHeading title="Dashboard" subtitle="Live numbers from the services" />
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={12}>
          <Panel title="Mini apps by status" query={overview} extra={<Link to="/apps">All apps</Link>}>
            {(d) => (
              <Space size="large" wrap>
                <Statistic title="Total" value={d.apps.total} />
                {(["LIVE", "DRAFT", "PAUSED", "KILLED"] as const).map((s) => (
                  <Statistic key={s} title={<Link to={`/apps?status=${s}`}>{s}</Link>} value={d.apps[s] ?? 0} />
                ))}
              </Space>
            )}
          </Panel>
        </Col>
        <Col xs={24} lg={12}>
          <Panel title="Moderation" query={overview} extra={<Link to="/moderation">Open</Link>}>
            {(d) => (
              <Space size="large" wrap>
                <Statistic title="Open reports" value={d.reports.open} valueStyle={d.reports.open > 0 ? { color: "#cf1322" } : undefined} />
                <Statistic title="Reports total" value={d.reports.total} />
                <Statistic title="Reviews" value={d.reviews.total} />
                <Statistic title="Hidden reviews" value={d.reviews.hidden} />
                <Statistic title="New reviews (7 days)" value={d.reviews.last7Days} />
              </Space>
            )}
          </Panel>
        </Col>
        <Col xs={24} lg={12}>
          <Panel title="Users" query={userStats} extra={<Link to="/users">Open</Link>}>
            {(d) => (
              <Space size="large" wrap>
                <Statistic title="Registered" value={d.total} />
                <Statistic title="Active" value={d.active} />
                <Statistic title="Suspended" value={d.suspended} />
                <Statistic title="Deleted" value={d.deleted} />
                <Statistic title="New (7 days)" value={d.createdLast7Days} />
              </Space>
            )}
          </Panel>
        </Col>
        <Col xs={24} lg={12}>
          <Panel title="Notifications" query={notifStats} extra={<Link to="/notifications">Open</Link>}>
            {(d) => (
              <div>
                <Space size="large" wrap>
                  <Statistic title="Total" value={d.total} />
                  <Statistic title="Last 24 hours" value={d.last24h.total} />
                  {Object.entries(d.last24h.byStatus).map(([k, v]) => (
                    <Statistic key={k} title={`${k} (24 h)`} value={v} />
                  ))}
                </Space>
              </div>
            )}
          </Panel>
        </Col>
        <Col xs={24} lg={12}>
          <Panel title="Apps not ready for LIVE" query={overview}>
            {(d) =>
              d.draftApps.length === 0 ? (
                <Typography.Text type="secondary">No draft apps.</Typography.Text>
              ) : (
                <Table
                  size="small"
                  pagination={false}
                  rowKey="id"
                  dataSource={d.draftApps}
                  columns={[
                    { title: "App", render: (_, a) => <Link to={`/apps/${a.id}`}>{i18nText(a.name) || a.slug}</Link> },
                    { title: "Missing for LIVE", render: (_, a) => (a.missingForLive.length ? a.missingForLive.join(", ") : "ready: nothing missing, set it LIVE when you want") },
                  ]}
                />
              )
            }
          </Panel>
        </Col>
        <Col xs={24} lg={12}>
          <Panel title="Open reports" query={openReports} extra={<Link to="/moderation">Moderation</Link>}>
            {({ rows }) =>
              rows.length === 0 ? (
                <Typography.Text type="secondary">No open reports.</Typography.Text>
              ) : (
                <Table
                  size="small"
                  pagination={false}
                  rowKey="id"
                  dataSource={rows}
                  columns={[
                    { title: "When", render: (_, r) => <Time value={r.createdAt} /> },
                    { title: "App", render: (_, r) => <Link to={`/apps/${r.appId}?tab=reports`}>{r.appSlug ?? shortId(r.appId)}</Link> },
                    { title: "Reason", dataIndex: "reason" },
                  ]}
                />
              )
            }
          </Panel>
        </Col>
        <Col xs={24} lg={12}>
          <Panel title="Recent notifications (all apps)" query={recentNotifications} extra={<Link to="/notifications">All</Link>}>
            {({ rows }) =>
              rows.length === 0 ? (
                <Typography.Text type="secondary">Nothing sent yet.</Typography.Text>
              ) : (
                <Table
                  size="small"
                  pagination={false}
                  rowKey="id"
                  dataSource={rows}
                  columns={[
                    { title: "When", render: (_, r) => <Time value={r.createdAt} /> },
                    { title: "Channel", dataIndex: "channel" },
                    { title: "Status", render: (_, r) => <StatusTag value={r.status} /> },
                    { title: "", render: (_, r) => <Link to={`/notifications/${r.id}`}>details</Link> },
                  ]}
                />
              )
            }
          </Panel>
        </Col>
        {canAudit && (
          <Col xs={24} lg={12}>
            <Panel title="Recent staff activity" query={audit} extra={<Link to="/audit">Audit log</Link>}>
              {({ rows }) => (
                <Table
                  size="small"
                  pagination={false}
                  rowKey="id"
                  dataSource={rows}
                  columns={[
                    { title: "When", render: (_, r) => <Time value={r.at} /> },
                    { title: "Who", render: (_, r) => r.actorEmail ?? shortId(r.actorId) },
                    { title: "Action", dataIndex: "action" },
                  ]}
                />
              )}
            </Panel>
          </Col>
        )}
      </Row>
      {!canAudit && <Alert style={{ marginTop: 12 }} type="info" showIcon message="You are signed in as a viewer: pages are read-only, and the audit log is for admins." />}
    </div>
  );
}
