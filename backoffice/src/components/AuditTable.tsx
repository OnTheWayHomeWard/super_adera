import { useTable } from "@refinedev/antd";
import { Alert, Button, DatePicker, Form, Input, Select, Space, Table } from "antd";
import type { Dayjs } from "dayjs";
import { useMemo, useState } from "react";
import { Link } from "react-router";
import { shortId } from "../lib/format";
import type { AuditEvent } from "../lib/types";
import { ErrorAlert, Json, Mono, Time, useCan } from "./common";

interface Filters {
  actor?: string;
  action?: string;
  targetType?: string;
  target?: string;
  range?: [Dayjs | null, Dayjs | null] | null;
}

/** Link to the page about the thing an event touched, when there is one. */
export function targetLink(e: Pick<AuditEvent, "targetType" | "targetId">): string | null {
  if (!e.targetId) return null;
  if (e.targetType === "mini_app") return `/apps/${e.targetId}`;
  if (e.targetType === "app_user") return `/users/${e.targetId}`;
  return null;
}

/** The audit log with filters (actor, action, target, date range). Pass `target` to pin it to one thing. */
export function AuditTable({ pinnedTarget, staff }: { pinnedTarget?: string; staff?: { id: string; email: string }[] }) {
  const canRead = useCan("audit.read");
  const [applied, setApplied] = useState<Filters>({});
  const permanent = useMemo(() => {
    const out: { field: string; operator: "eq"; value: string }[] = [];
    if (pinnedTarget) out.push({ field: "target", operator: "eq", value: pinnedTarget });
    const f = applied;
    if (f.actor) out.push({ field: "actor", operator: "eq", value: f.actor });
    if (f.action) out.push({ field: "action", operator: "eq", value: f.action });
    if (f.targetType) out.push({ field: "targetType", operator: "eq", value: f.targetType });
    if (!pinnedTarget && f.target) out.push({ field: "target", operator: "eq", value: f.target });
    if (f.range?.[0]) out.push({ field: "from", operator: "eq", value: f.range[0].toDate().toISOString() });
    if (f.range?.[1]) out.push({ field: "to", operator: "eq", value: f.range[1].toDate().toISOString() });
    return out;
  }, [applied, pinnedTarget]);

  const { tableProps, tableQuery } = useTable<AuditEvent>({
    resource: "audit",
    pagination: { pageSize: 50 },
    filters: { permanent },
    queryOptions: { enabled: canRead },
    syncWithLocation: false,
  });
  const [form] = Form.useForm<Filters>();

  if (!canRead) return <Alert type="info" showIcon message="The audit log is for admins and super admins." />;

  return (
    <div>
      <Form form={form} layout="inline" style={{ marginBottom: 12, rowGap: 8 }} onFinish={(v) => setApplied(v)}>
        <Form.Item name="actor" label="Actor">
          {staff ? (
            <Select allowClear showSearch optionFilterProp="label" style={{ width: 240 }} placeholder="Anyone" options={staff.map((s) => ({ value: s.id, label: s.email }))} />
          ) : (
            <Input allowClear placeholder="Staff id" style={{ width: 280 }} />
          )}
        </Form.Item>
        <Form.Item name="action" label="Action" extra={undefined}>
          <Input allowClear placeholder="e.g. app. or app.status" style={{ width: 200 }} />
        </Form.Item>
        <Form.Item name="targetType" label="Target type">
          <Select allowClear style={{ width: 150 }} placeholder="Any" options={["mini_app", "partner_key", "staff", "app_review", "app_report", "app_user", "template"].map((v) => ({ value: v }))} />
        </Form.Item>
        {!pinnedTarget && (
          <Form.Item name="target" label="Target id">
            <Input allowClear placeholder="App, user, key or staff id" style={{ width: 280 }} />
          </Form.Item>
        )}
        <Form.Item name="range" label="Between">
          <DatePicker.RangePicker showTime />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              Filter
            </Button>
            <Button
              onClick={() => {
                form.resetFields();
                setApplied({});
              }}
            >
              Clear
            </Button>
          </Space>
        </Form.Item>
      </Form>
      <p style={{ opacity: 0.7, marginTop: 0 }}>The action filter matches the start of the action name (app. matches app.create, app.status...).</p>
      <ErrorAlert error={tableQuery.error} />
      <Table<AuditEvent>
        {...tableProps}
        rowKey="id"
        size="small"
        scroll={{ x: "max-content" }}
        expandable={{ expandedRowRender: (e) => <Json value={e.meta} maxHeight={200} /> }}
        columns={[
          { title: "When", render: (_, e) => <Time value={e.at} /> },
          { title: "Actor", render: (_, e) => (e.actorEmail ?? staff?.find((s) => s.id === e.actorId)?.email) ?? (e.actorId ? shortId(e.actorId) : <i>system</i>) },
          { title: "Role", dataIndex: "actorRole", render: (v) => v ?? "-" },
          { title: "Action", dataIndex: "action", render: (v) => <Mono>{v}</Mono> },
          { title: "Target", render: (_, e) => (e.targetId ? (targetLink(e) ? <Link to={targetLink(e)!}>{e.targetType}: {shortId(e.targetId)}</Link> : `${e.targetType ?? ""}: ${e.targetId.length > 24 ? shortId(e.targetId) : e.targetId}`) : "-") },
          { title: "Details", render: (_, e) => <span style={{ opacity: 0.8 }}>{summarise(e.meta)}</span> },
          { title: "IP", dataIndex: "ip", render: (v) => v ?? "-" },
        ]}
      />
    </div>
  );
}

function summarise(meta: Record<string, unknown>): string {
  const text = Object.entries(meta)
    .map(([k, v]) => `${k}=${typeof v === "object" ? JSON.stringify(v) : String(v)}`)
    .join(" ");
  return text.length > 90 ? `${text.slice(0, 90)}...` : text;
}
