import { useTable } from "@refinedev/antd";
import { Rate, Select, Space, Table, Tag, Typography } from "antd";
import { useMemo } from "react";
import { Link } from "react-router";
import { api } from "../lib/client";
import { shortId } from "../lib/format";
import type { Report, Review } from "../lib/types";
import { ConfirmButton, ErrorAlert, LongText, RoleGate, StatusTag, Time } from "./common";
import { Button } from "antd";

function useFilters(appId: string | undefined, initial: { field: string; value: string }[] = []) {
  const permanent = useMemo(() => (appId ? [{ field: "appId", operator: "eq" as const, value: appId }] : []), [appId]);
  return { permanent, initial: initial.map((f) => ({ field: f.field, operator: "eq" as const, value: f.value })) };
}

function filterValue(filters: { field?: string; value?: unknown }[], field: string): string | undefined {
  const f = filters.find((x) => x.field === field);
  return f ? (f.value as string) : undefined;
}

export function ReviewsTable({ appId }: { appId?: string }) {
  const { permanent } = useFilters(appId);
  const { tableProps, tableQuery, filters, setFilters } = useTable<Review>({
    resource: "reviews",
    pagination: { pageSize: 25 },
    filters: { permanent },
    syncWithLocation: false,
  });
  const status = filterValue(filters as never, "status");
  const moderate = async (r: Review, next: "VISIBLE" | "HIDDEN") => {
    await api(`reviews/${r.id}`, { method: "PATCH", body: { status: next } });
    await tableQuery.refetch();
  };
  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="Any status"
          style={{ width: 150 }}
          value={status}
          options={[{ value: "VISIBLE" }, { value: "HIDDEN" }]}
          onChange={(v) => setFilters(v ? [{ field: "status", operator: "eq", value: v }] : [], "replace")}
        />
        <Typography.Text type="secondary">Hidden reviews stay here but users no longer see them, and they do not count toward the rating.</Typography.Text>
      </Space>
      <ErrorAlert error={tableQuery.error} />
      <Table<Review>
        {...tableProps}
        rowKey="id"
        size="small"
        scroll={{ x: "max-content" }}
        columns={[
          { title: "When", className: "nowrap", render: (_, r) => <Time value={r.createdAt} /> },
          ...(appId ? [] : [{ title: "App", render: (_: unknown, r: Review) => <Link to={`/apps/${r.appId}?tab=reviews`}>{r.appSlug ?? shortId(r.appId)}</Link> }]),
          { title: "Rating", render: (_, r) => <Rate disabled value={r.rating} style={{ fontSize: 14 }} /> },
          { title: "Comment", dataIndex: "comment", render: (v) => <LongText value={v} empty="(no comment)" /> },
          { title: "User", render: (_, r) => <Link to={`/users/${r.userId}`}>{shortId(r.userId)}</Link> },
          { title: "Status", render: (_, r) => <StatusTag value={r.status} /> },
          {
            title: "",
            fixed: "right" as const,
            render: (_, r) => (
              <RoleGate ability="write">
                {r.status === "VISIBLE" ? (
                  <ConfirmButton
                    label="Hide"
                    danger
                    title="Hide this review?"
                    description="Users will no longer see it and the app's rating is recalculated. You can restore it later."
                    onConfirm={() => moderate(r, "HIDDEN")}
                  />
                ) : (
                  <Button size="small" onClick={() => moderate(r, "VISIBLE")}>
                    Restore
                  </Button>
                )}
              </RoleGate>
            ),
          },
        ]}
      />
    </div>
  );
}

export function ReportsTable({ appId, initialStatus }: { appId?: string; initialStatus?: string }) {
  const { permanent, initial } = useFilters(appId, initialStatus ? [{ field: "status", value: initialStatus }] : []);
  const { tableProps, tableQuery, filters, setFilters } = useTable<Report>({
    resource: "reports",
    pagination: { pageSize: 25 },
    filters: { permanent, initial },
    syncWithLocation: false,
  });
  const status = filterValue(filters as never, "status");
  const handle = async (r: Report, next: Report["status"]) => {
    await api(`reports/${r.id}`, { method: "PATCH", body: { status: next } });
    await tableQuery.refetch();
  };
  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="Any status"
          style={{ width: 160 }}
          value={status}
          options={[{ value: "OPEN" }, { value: "REVIEWED" }, { value: "DISMISSED" }]}
          onChange={(v) => setFilters(v ? [{ field: "status", operator: "eq", value: v }] : [], "replace")}
        />
        <Typography.Text type="secondary">REVIEWED = acted on. DISMISSED = no action needed. Both can be reopened.</Typography.Text>
      </Space>
      <ErrorAlert error={tableQuery.error} />
      <Table<Report>
        {...tableProps}
        rowKey="id"
        size="small"
        scroll={{ x: "max-content" }}
        columns={[
          { title: "Reported", className: "nowrap", render: (_, r) => <Time value={r.createdAt} /> },
          ...(appId ? [] : [{ title: "App", render: (_: unknown, r: Report) => <Link to={`/apps/${r.appId}?tab=reports`}>{r.appSlug ?? shortId(r.appId)}</Link> }]),
          { title: "Reason", dataIndex: "reason", render: (v) => <Tag>{v}</Tag> },
          { title: "Details", dataIndex: "details", render: (v) => <LongText value={v} empty="(none)" /> },
          { title: "By user", render: (_, r) => <Link to={`/users/${r.userId}`}>{shortId(r.userId)}</Link> },
          { title: "Status", render: (_, r) => <StatusTag value={r.status} /> },
          { title: "Handled", render: (_, r) => (r.handledAt ? <Time value={r.handledAt} /> : "-") },
          {
            title: "",
            fixed: "right" as const,
            render: (_, r) => (
              <RoleGate ability="write">
                <Space>
                  {r.status !== "REVIEWED" && (
                    <Button size="small" type="primary" onClick={() => handle(r, "REVIEWED")}>
                      Resolve
                    </Button>
                  )}
                  {r.status !== "DISMISSED" && (
                    <ConfirmButton label="Dismiss" title="Dismiss this report?" description="Marks it as needing no action." onConfirm={() => handle(r, "DISMISSED")} />
                  )}
                  {r.status !== "OPEN" && (
                    <Button size="small" onClick={() => handle(r, "OPEN")}>
                      Reopen
                    </Button>
                  )}
                </Space>
              </RoleGate>
            ),
          },
        ]}
      />
    </div>
  );
}
