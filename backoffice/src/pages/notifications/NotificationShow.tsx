import { Alert, Card, Descriptions, Spin, Table, Typography } from "antd";
import { Link, useParams } from "react-router";
import { ErrorAlert, Mono, PageHeading, StatusTag, Time } from "../../components/common";
import { useApiQuery } from "../../lib/hooks";
import { explainNotification } from "../../lib/notificationHelp";
import type { NotificationDetail } from "../../lib/types";

export function NotificationShowPage() {
  const { id = "" } = useParams();
  const q = useApiQuery<NotificationDetail>(["notification", id], `notification/notifications/${id}`);
  if (q.isLoading) return <Spin />;
  if (q.error || !q.data) return <div><ErrorAlert error={q.error} title="Could not load the notification" /><Link to="/notifications">Back</Link></div>;
  const n = q.data;
  const explanation = explainNotification(n, n);
  const bad = ["FAILED", "UNREACHABLE", "EXPIRED", "SUPPRESSED"].includes(n.status);
  return (
    <div>
      <PageHeading title={<span>Notification <StatusTag value={n.status} /></span>} subtitle={<Mono>{n.id}</Mono>} extra={<Link to="/notifications">All notifications</Link>} />
      <Alert
        type={bad ? "warning" : n.status === "SENT" ? "success" : "info"}
        showIcon
        style={{ marginBottom: 12 }}
        message={bad ? "Why did this not arrive?" : "What happened"}
        description={explanation.map((l) => <div key={l}>{l}</div>)}
      />
      <Card size="small" title="Request">
        <Descriptions size="small" bordered column={{ xs: 1, md: 2 }}>
          <Descriptions.Item label="App"><Link to={`/apps/${n.appId}?tab=log`}>{n.appId}</Link></Descriptions.Item>
          <Descriptions.Item label="Partner's external id">{n.externalId}</Descriptions.Item>
          <Descriptions.Item label="Environment">{n.environment}</Descriptions.Item>
          <Descriptions.Item label="Kind / channel">{n.kind} / {n.channel}</Descriptions.Item>
          <Descriptions.Item label="Template">{n.templateId} (version {n.templateVersion})</Descriptions.Item>
          <Descriptions.Item label="Status reason">{n.reason ?? "-"}</Descriptions.Item>
          <Descriptions.Item label="Attempts">{n.attempts}</Descriptions.Item>
          <Descriptions.Item label="Created"><Time value={n.createdAt} /></Descriptions.Item>
          {!n.completedAt && <Descriptions.Item label="Next attempt"><Time value={n.nextAttemptAt} /></Descriptions.Item>}
          <Descriptions.Item label="Expires"><Time value={n.expiresAt} /></Descriptions.Item>
          <Descriptions.Item label="Completed"><Time value={n.completedAt} /></Descriptions.Item>
        </Descriptions>
        <Typography.Text type="secondary">The message values and the recipient are deliberately not shown here.</Typography.Text>
      </Card>
      <Card size="small" title="Delivery attempts" style={{ marginTop: 12 }}>
        <Table
          size="small"
          rowKey={(a) => `${a.at}-${a.provider}`}
          pagination={false}
          dataSource={n.attemptLog}
          locale={{ emptyText: "No attempt was made." }}
          columns={[
            { title: "When", render: (_, a) => <Time value={a.at} /> },
            { title: "Channel", dataIndex: "channel" },
            { title: "Provider", dataIndex: "provider" },
            { title: "Outcome", dataIndex: "outcome" },
            { title: "Error code", dataIndex: "errorCode", render: (v) => v ?? "-" },
          ]}
        />
      </Card>
      <Card size="small" title="Callbacks to the partner" style={{ marginTop: 12 }}>
        <Table
          size="small"
          rowKey={(c) => `${c.status}-${c.nextAttemptAt}-${c.attempts}`}
          pagination={false}
          dataSource={n.callbacks}
          locale={{ emptyText: "No callback was requested or none is pending." }}
          columns={[
            { title: "Status", render: (_, c) => <StatusTag value={c.status} /> },
            { title: "Attempts", dataIndex: "attempts" },
            { title: "Last HTTP status", dataIndex: "lastHttpStatus", render: (v) => v ?? "-" },
            { title: "Last error", dataIndex: "lastError", render: (v) => v ?? "-" },
            { title: "Next attempt", render: (_, c) => <Time value={c.nextAttemptAt} /> },
          ]}
        />
      </Card>
    </div>
  );
}
