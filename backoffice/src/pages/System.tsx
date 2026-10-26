import { Button, Card, Col, Row, Space, Table, Tabs, Tag, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { EnvTag, ErrorAlert, Mono, PageHeading } from "../components/common";
import { apiRequest } from "../lib/client";
import { getConfig } from "../lib/config";
import { ERROR_GLOSSARY, PARTNER_CODES } from "../lib/glossary";
import { useApiQuery, useDeploymentEnvironment } from "../lib/hooks";
import type { CapabilityInfo } from "../lib/types";

const SERVICES = [
  { key: "registry", label: "registry-service", note: "Mini apps, staff, reviews, audit" },
  { key: "user", label: "user-service", note: "Users, devices, aliases" },
  { key: "notification", label: "notification-service", note: "Delivery, templates, partner API" },
  { key: "kratos", label: "Ory Kratos", note: "Identity and sessions" },
  { key: "prometheus", label: "Prometheus", note: "Metrics" },
  { key: "grafana", label: "Grafana", note: "Dashboards" },
  { key: "loki", label: "Loki", note: "Logs" },
  { key: "mailpit", label: "Mailpit", note: "Dev inbox" },
  { key: "infisical", label: "Infisical", note: "Secrets" },
];

interface Health {
  key: string;
  up: boolean;
  code: number | null;
  detail: string;
}

/** One health probe through the dashboard's own proxy (it reaches the services on the internal network). */
export async function probe(key: string): Promise<Health> {
  try {
    const r = await apiRequest<unknown>(`/backoffice/api/health/${key}`, { quiet: true });
    const status = r.data && typeof r.data === "object" && "status" in (r.data as object) ? String((r.data as { status: unknown }).status) : "";
    return { key, up: !status || /^(UP|ok|OK|ready|healthy)/i.test(status), code: 200, detail: status || "answered 200" };
  } catch (e) {
    const status = (e as { status?: number }).status ?? null;
    return { key, up: false, code: status, detail: status ? `HTTP ${status}` : "no answer" };
  }
}

export function SystemPage() {
  const cfg = getConfig();
  const health = useQuery({
    queryKey: ["bo", "health"],
    queryFn: () => Promise.all(SERVICES.map((s) => probe(s.key))),
    refetchInterval: 30_000,
  });
  const caps = useApiQuery<CapabilityInfo[]>(["capabilities"], "capabilities");
  const deployment = useDeploymentEnvironment();

  const links = [
    ["Grafana (dashboards)", cfg.grafanaUrl],
    ["Prometheus (metrics)", cfg.prometheusUrl],
    ["Mailpit (dev inbox)", cfg.mailpitUrl],
    ["Infisical (secrets)", cfg.infisicalUrl],
    ["RabbitMQ management", cfg.rabbitmqUrl],
    ["RustFS console (files)", cfg.rustfsConsoleUrl],
  ].filter((l): l is [string, string] => !!l[1]);

  return (
    <div>
      <PageHeading title="System" subtitle="Tools, service health and reference tables" />
      {deployment && (
        <Typography.Paragraph>
          This deployment runs <EnvTag value={deployment} /> (setting ADERA_ENV). Keys and allowed addresses of the other environment can be stored but are not used here.
        </Typography.Paragraph>
      )}
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={8}>
          <Card size="small" title="Tools">
            {links.length === 0 ? (
              <Typography.Text type="secondary">No tool links are configured (see BACKOFFICE_* settings).</Typography.Text>
            ) : (
              <Space direction="vertical">
                {links.map(([label, url]) => (
                  <a key={url} href={url} target="_blank" rel="noreferrer">
                    {label} <Typography.Text type="secondary">{url}</Typography.Text>
                  </a>
                ))}
              </Space>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={16}>
          <Card size="small" title="Service health" extra={<Button size="small" onClick={() => health.refetch()} loading={health.isFetching}>Check now</Button>}>
            <ErrorAlert error={health.error} />
            <Table
              size="small"
              rowKey="key"
              pagination={false}
              loading={health.isLoading}
              dataSource={SERVICES.map((s) => ({ ...s, h: health.data?.find((x) => x.key === s.key) }))}
              columns={[
                { title: "Service", dataIndex: "label" },
                { title: "What for", dataIndex: "note" },
                { title: "State", render: (_, r) => (r.h ? <Tag color={r.h.up ? "green" : "red"}>{r.h.up ? "UP" : "DOWN"}</Tag> : "-") },
                { title: "Detail", render: (_, r) => r.h?.detail ?? "-" },
              ]}
            />
            <Typography.Text type="secondary">Checked every 30 seconds through the dashboard server, which reaches the services on the internal network. The gateway itself is up if this page loaded.</Typography.Text>
          </Card>
        </Col>
      </Row>
      <Tabs
        style={{ marginTop: 12 }}
        items={[
          {
            key: "caps",
            label: "Capability catalog",
            children: (
              <div>
                <ErrorAlert error={caps.error} />
                <Table<CapabilityInfo>
                  size="small"
                  rowKey="key"
                  pagination={false}
                  loading={caps.isLoading}
                  dataSource={caps.data ?? []}
                  columns={[
                    { title: "Capability", render: (_, c) => <Mono>{c.key}</Mono> },
                    { title: "Description", dataIndex: "description" },
                    { title: "Reason required to grant", render: (_, c) => (c.reasonRequired ? "yes" : "no") },
                  ]}
                />
              </div>
            ),
          },
          { key: "errors", label: "Error codes (staff API)", children: <Glossary rows={ERROR_GLOSSARY} /> },
          { key: "partner", label: "Partner-facing codes", children: <Glossary rows={PARTNER_CODES} /> },
        ]}
      />
    </div>
  );
}

function Glossary({ rows }: { rows: { code: string; http: string; meaning: string }[] }) {
  return (
    <Table size="small" rowKey="code" pagination={false} dataSource={rows} columns={[{ title: "Code", render: (_, r) => <Mono>{r.code}</Mono> }, { title: "HTTP", dataIndex: "http", width: 70 }, { title: "Meaning", dataIndex: "meaning" }]} />
  );
}
