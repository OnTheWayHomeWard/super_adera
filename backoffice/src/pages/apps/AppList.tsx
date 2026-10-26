import { PlusOutlined } from "@ant-design/icons";
import { useTable } from "@refinedev/antd";
import { Avatar, Button, Input, Select, Space, Table } from "antd";
import { useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { ErrorAlert, PageHeading, RoleGate, StatusTag, Time } from "../../components/common";
import { i18nText } from "../../lib/format";
import { APP_STATUSES, type AppView } from "../../lib/types";

export function AppListPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const status = params.get("status") ?? undefined;
  const [search, setSearch] = useState("");
  const { tableProps, tableQuery } = useTable<AppView>({
    resource: "apps",
    pagination: { mode: "off" },
    filters: { permanent: status ? [{ field: "status", operator: "eq", value: status }] : [] },
    syncWithLocation: false,
  });

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    const data = (tableProps.dataSource ?? []) as AppView[];
    if (!q) return data;
    return data.filter((a) =>
      [a.slug, ...Object.values(a.name), a.publisher.name ?? "", a.publisher.legalName ?? "", a.publisher.supportEmail ?? "", ...a.categories].some((s) => s.toLowerCase().includes(q)),
    );
  }, [tableProps.dataSource, search]);

  return (
    <div>
      <PageHeading
        title="Mini apps"
        subtitle="Ordered by priority (lower first), then slug. Click a column heading to sort."
        extra={
          <RoleGate ability="write">
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate("/apps/create")}>
              New app
            </Button>
          </RoleGate>
        }
      />
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="Any status"
          style={{ width: 160 }}
          value={status}
          options={APP_STATUSES.map((s) => ({ value: s, label: s }))}
          onChange={(v) => setParams(v ? { status: v } : {})}
        />
        <Input.Search allowClear placeholder="Search name, slug, publisher, category" style={{ width: 340 }} onChange={(e) => setSearch(e.target.value)} />
        <span>{rows.length} apps</span>
      </Space>
      <ErrorAlert error={tableQuery.error} />
      <Table<AppView>
        {...tableProps}
        dataSource={rows}
        rowKey="id"
        size="small"
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        onRow={(r) => ({ onDoubleClick: () => navigate(`/apps/${r.id}`) })}
        columns={[
          { title: "", width: 48, render: (_, a) => <Avatar shape="square" src={a.logoUrl ?? undefined}>{(a.slug[0] ?? "?").toUpperCase()}</Avatar> },
          { title: "Name", sorter: (a, b) => i18nText(a.name).localeCompare(i18nText(b.name)), render: (_, a) => <Link to={`/apps/${a.id}`}>{i18nText(a.name) || a.slug}</Link> },
          { title: "Slug", dataIndex: "slug", sorter: (a, b) => a.slug.localeCompare(b.slug) },
          { title: "Status", dataIndex: "status", sorter: (a, b) => a.status.localeCompare(b.status), render: (v) => <StatusTag value={v} /> },
          { title: "Priority", dataIndex: "priority", sorter: (a, b) => a.priority - b.priority, defaultSortOrder: "ascend" },
          { title: "Publisher", render: (_, a) => a.publisher.name ?? "-" },
          { title: "Rating", sorter: (a, b) => a.ratingAvg - b.ratingAvg, render: (_, a) => (a.ratingCount ? `${Number(a.ratingAvg).toFixed(1)} (${a.ratingCount})` : "-") },
          { title: "Updated", dataIndex: "updatedAt", sorter: (a, b) => a.updatedAt.localeCompare(b.updatedAt), render: (v) => <Time value={v} /> },
        ]}
      />
    </div>
  );
}
