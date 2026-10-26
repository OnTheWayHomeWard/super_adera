import { useTable } from "@refinedev/antd";
import { Alert, Input, Select, Space, Table, Typography } from "antd";
import { useState } from "react";
import { Link } from "react-router";
import { ErrorAlert, PageHeading, StatusTag, Time, useCan } from "../../components/common";
import { userName } from "../../lib/format";
import type { UserSummary } from "../../lib/types";

export function UsersPage() {
  const canSeeAll = useCan("user.pii");
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<string | undefined>();
  const { tableProps, tableQuery, setFilters } = useTable<UserSummary>({
    resource: "users",
    pagination: { pageSize: 25 },
    filters: { permanent: [] },
    syncWithLocation: false,
  });

  const apply = (nextQ: string, nextStatus: string | undefined) =>
    setFilters(
      [
        ...(nextQ.trim() ? [{ field: "q", operator: "eq" as const, value: nextQ.trim() }] : []),
        ...(nextStatus ? [{ field: "status", operator: "eq" as const, value: nextStatus }] : []),
      ],
      "replace",
    );

  return (
    <div>
      <PageHeading title="Users" subtitle="Registered users (customers)" />
      {!canSeeAll && <Alert type="info" showIcon style={{ marginBottom: 12 }} message="Phone numbers and emails are masked for your role. You can search by name or user id; searching by phone or email needs the ADMIN role." />}
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          allowClear
          style={{ width: 380 }}
          placeholder="Search phone, email, name or user id"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onSearch={(v) => apply(v, status)}
          enterButton="Search"
        />
        <Select
          allowClear
          placeholder="Any status"
          style={{ width: 150 }}
          value={status}
          options={["ACTIVE", "SUSPENDED", "DELETED"].map((s) => ({ value: s }))}
          onChange={(v) => {
            setStatus(v);
            apply(q, v);
          }}
        />
        <Typography.Text type="secondary">Phone search: type digits, with or without +251.</Typography.Text>
      </Space>
      <ErrorAlert error={tableQuery.error} />
      <Table<UserSummary>
        {...tableProps}
        rowKey="id"
        size="small"
        scroll={{ x: "max-content" }}
        columns={[
          { title: "Name", render: (_, u) => <Link to={`/users/${u.id}`}>{userName(u) || <i>(no name)</i>}</Link> },
          { title: "Phone", dataIndex: "phone", render: (v) => v ?? "-" },
          { title: "Email", dataIndex: "email", render: (v) => v ?? "-" },
          { title: "Status", render: (_, u) => <StatusTag value={u.status} /> },
          { title: "Market", dataIndex: "market" },
          { title: "Language", dataIndex: "language" },
          { title: "Tier", dataIndex: "verificationTier" },
          { title: "Registered", render: (_, u) => <Time value={u.createdAt} /> },
          { title: "Last seen", render: (_, u) => <Time value={u.lastSeenAt} /> },
        ]}
      />
    </div>
  );
}
