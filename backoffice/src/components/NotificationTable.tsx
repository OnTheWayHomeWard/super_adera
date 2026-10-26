import { useTable } from "@refinedev/antd";
import { Select, Space, Table } from "antd";
import { Link } from "react-router";
import { shortId } from "../lib/format";
import { CHANNELS, ENVIRONMENTS, type NotificationRow } from "../lib/types";
import { ErrorAlert, StatusTag, Time } from "./common";
import { useMemo } from "react";

const STATUSES = ["QUEUED", "PROCESSING", "RETRYING", "SENT", "FAILED", "UNREACHABLE", "SUPPRESSED", "EXPIRED"];

/** Delivery log, for one app or across all apps. Never shows message values: the API does not return them. */
export function NotificationTable({ appId, appNames }: { appId?: string; appNames?: Record<string, string> }) {
  const permanent = useMemo(() => (appId ? [{ field: "appId", operator: "eq" as const, value: appId }] : []), [appId]);
  const { tableProps, tableQuery, filters, setFilters } = useTable<NotificationRow>({
    resource: "notifications",
    pagination: { pageSize: 25 },
    filters: { permanent },
    syncWithLocation: false,
  });
  const current = (field: string) => {
    const f = filters.find((x) => "field" in x && x.field === field);
    return f && "value" in f ? (f.value as string) : undefined;
  };
  const set = (field: string, value: string | undefined) =>
    setFilters([...filters.filter((f) => !("field" in f) || f.field !== field), ...(value ? [{ field, operator: "eq" as const, value }] : [])], "replace");

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <Select allowClear placeholder="Any status" style={{ width: 150 }} value={current("status")} options={STATUSES.map((s) => ({ value: s, label: s }))} onChange={(v) => set("status", v)} />
        <Select allowClear placeholder="Any channel" style={{ width: 150 }} value={current("channel")} options={CHANNELS.map((s) => ({ value: s, label: s }))} onChange={(v) => set("channel", v)} />
        <Select allowClear placeholder="Any environment" style={{ width: 160 }} value={current("environment")} options={ENVIRONMENTS.map((s) => ({ value: s, label: s }))} onChange={(v) => set("environment", v)} />
        <Select allowClear placeholder="Any kind" style={{ width: 140 }} value={current("kind")} options={["DIRECT", "IN_APP"].map((s) => ({ value: s, label: s }))} onChange={(v) => set("kind", v)} />
      </Space>
      <ErrorAlert error={tableQuery.error} />
      <Table<NotificationRow>
        {...tableProps}
        rowKey="id"
        size="small"
        scroll={{ x: "max-content" }}
        columns={[
          { title: "Created", className: "nowrap", render: (_, n) => <Time value={n.createdAt} /> },
          ...(appId ? [] : [{ title: "App", render: (_: unknown, n: NotificationRow) => (n.appId ? <Link to={`/apps/${n.appId}`}>{appNames?.[n.appId] ?? shortId(n.appId)}</Link> : "-") }]),
          { title: "External id", dataIndex: "externalId", className: "nowrap" },
          { title: "Env", dataIndex: "environment" },
          { title: "Kind", dataIndex: "kind" },
          { title: "Channel", dataIndex: "channel" },
          { title: "Status", render: (_, n) => <StatusTag value={n.status} /> },
          { title: "Reason", dataIndex: "reason", render: (v) => v ?? "-" },
          { title: "Attempts", dataIndex: "attempts" },
          { title: "Completed", className: "nowrap", render: (_, n) => <Time value={n.completedAt} /> },
          { title: "", fixed: "right" as const, render: (_, n) => <Link className="nowrap" to={`/notifications/${n.id}`}>details</Link> },
        ]}
      />
    </div>
  );
}
