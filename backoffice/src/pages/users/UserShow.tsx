import { Alert, Card, Col, Descriptions, Row, Space, Spin, Table, Typography } from "antd";
import { Link, useParams } from "react-router";
import { AuditTable } from "../../components/AuditTable";
import { ConfirmButton, CopyButton, ErrorAlert, Json, Mono, PageHeading, RoleGate, StatusTag, Time, useCan } from "../../components/common";
import { api } from "../../lib/client";
import { i18nText, userName } from "../../lib/format";
import { useApiQuery, useRefreshKeys } from "../../lib/hooks";
import type { AppView, UserDetail } from "../../lib/types";

export function UserShowPage() {
  const { id = "" } = useParams();
  const canPii = useCan("user.pii");
  const refresh = useRefreshKeys();
  const user = useApiQuery<UserDetail>(["user", id], `users/${id}`);
  const apps = useApiQuery<AppView[]>(["apps"], "apps");

  if (user.isLoading) return <Spin />;
  if (user.error || !user.data) {
    return (
      <div>
        <ErrorAlert error={user.error} title="Could not load the user" />
        <Link to="/users">Back to users</Link>
      </div>
    );
  }
  const u = user.data;
  const appName = (appId: string) => {
    const a = apps.data?.find((x) => x.id === appId);
    return a ? i18nText(a.name) || a.slug : appId;
  };
  const setStatus = async (status: "ACTIVE" | "SUSPENDED" | "DELETED", reason: string) => {
    await api(`users/${id}/status`, { method: "POST", body: { status, ...(reason ? { reason } : {}) } });
    await refresh(["user", id], ["users"], ["user-stats"]);
  };

  return (
    <div>
      <PageHeading
        title={
          <Space>
            <span>{userName(u) || "(no name)"}</span>
            <StatusTag value={u.status} />
          </Space>
        }
        subtitle={
          <span>
            id <Mono>{u.id}</Mono> <CopyButton text={u.id} />
          </span>
        }
        extra={<Link to="/users">All users</Link>}
      />
      {u.masked && <Alert type="info" showIcon style={{ marginBottom: 12 }} message="Contact details, date of birth, gender and extra data are masked or hidden for your role (viewer)." />}

      <RoleGate ability="write">
        <Card size="small" title="Account status" style={{ marginBottom: 12 }}>
          <Space wrap>
            {u.status === "ACTIVE" && (
              <ConfirmButton
                label="Suspend"
                danger
                title="Suspend this user?"
                okText="Suspend"
                description="They can no longer use the API (sign-in checks are refused), and partner apps cannot reach them (user_unreachable). Reversible."
                reason={{ label: "Reason (written to the audit log)", minLength: 3, required: true }}
                onConfirm={(reason) => setStatus("SUSPENDED", reason)}
              />
            )}
            {u.status === "SUSPENDED" && (
              <ConfirmButton
                label="Reactivate"
                title="Reactivate this user?"
                okText="Reactivate"
                description="They can use the API again and partner apps can reach them."
                reason={{ label: "Note (optional, written to the audit log)", minLength: 3, required: false }}
                onConfirm={(reason) => setStatus("ACTIVE", reason)}
              />
            )}
            {u.status !== "DELETED" && (
              <ConfirmButton
                label="Mark deleted"
                danger
                title="Mark this user as deleted?"
                okText="Mark deleted"
                description={<Alert type="error" showIcon message="This cannot be undone." description="A deleted user is treated like a user who does not exist: no sign-in to the API and no partner delivery. Their data is not erased by this action." />}
                reason={{ label: "Reason (written to the audit log)", minLength: 3, required: true }}
                onConfirm={(reason) => setStatus("DELETED", reason)}
              />
            )}
            {u.status === "DELETED" && <Typography.Text type="secondary">Deleted users cannot be changed.</Typography.Text>}
          </Space>
        </Card>
      </RoleGate>

      <Row gutter={[12, 12]}>
        <Col xs={24}>
          <Card size="small" title="Profile">
            <Descriptions size="small" column={{ xs: 1, md: 2, xl: 3 }} bordered>
              <Descriptions.Item label="First name">{u.firstName ?? "-"}</Descriptions.Item>
              <Descriptions.Item label="Last name">{u.lastName ?? "-"}</Descriptions.Item>
              <Descriptions.Item label="Phone">{u.phone ?? "-"}</Descriptions.Item>
              <Descriptions.Item label="Email">{u.email ?? "-"}</Descriptions.Item>
              <Descriptions.Item label="Gender">{u.gender ?? (u.masked ? "hidden" : "-")}</Descriptions.Item>
              <Descriptions.Item label="Date of birth">{u.dateOfBirth ?? (u.masked ? "hidden" : "-")}</Descriptions.Item>
              <Descriptions.Item label="Language">{u.language}</Descriptions.Item>
              <Descriptions.Item label="Market">{u.market}</Descriptions.Item>
              <Descriptions.Item label="Timezone">{u.timezone}</Descriptions.Item>
              <Descriptions.Item label="Verification tier">{u.verificationTier}</Descriptions.Item>
              <Descriptions.Item label="Terms accepted">{u.termsAcceptedVersion ? `${u.termsAcceptedVersion} on ` : "no"}{u.termsAcceptedAt ? <Time value={u.termsAcceptedAt} /> : null}</Descriptions.Item>
              <Descriptions.Item label="Avatar">{u.avatarImageId ? "set" : "none"}</Descriptions.Item>
              <Descriptions.Item label="Registered"><Time value={u.createdAt} /></Descriptions.Item>
              <Descriptions.Item label="Last seen"><Time value={u.lastSeenAt} /></Descriptions.Item>
              <Descriptions.Item label="Updated"><Time value={u.updatedAt} /></Descriptions.Item>
              <Descriptions.Item label="Deleted at"><Time value={u.deletedAt} /></Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title="Location (summary)" style={{ height: "100%" }}>
            {u.location ? (
              <Descriptions size="small" column={1}>
                <Descriptions.Item label="Country">{u.location.countryCode ?? "-"}</Descriptions.Item>
                <Descriptions.Item label="Region">{u.location.region ?? "-"}</Descriptions.Item>
                <Descriptions.Item label="City">{u.location.city ?? "-"}</Descriptions.Item>
                <Descriptions.Item label="Precise position on file">{u.location.hasPreciseLocation ? "yes (coordinates are not shown here)" : "no"}</Descriptions.Item>
                <Descriptions.Item label="Updated"><Time value={u.location.updatedAt} /></Descriptions.Item>
              </Descriptions>
            ) : (
              <Typography.Text type="secondary">No location saved.</Typography.Text>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title="Devices and aliases" style={{ height: "100%" }}>
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="Devices">{u.devices.active} active of {u.devices.total}</Descriptions.Item>
              <Descriptions.Item label="Platforms">{u.devices.platforms.length ? u.devices.platforms.join(", ") : "-"}</Descriptions.Item>
              <Descriptions.Item label="Apps this user opened (pseudonymous ids)">{u.aliasCount}</Descriptions.Item>
            </Descriptions>
            <Typography.Text type="secondary">Push tokens and pseudonymous ids are never shown.</Typography.Text>
          </Card>
        </Col>
      </Row>

      <Card size="small" title="Mute preferences per app" style={{ marginTop: 12 }}>
        <Table
          size="small"
          rowKey="appId"
          pagination={false}
          dataSource={u.mutePreferences}
          locale={{ emptyText: "No per-app preferences (nothing is muted)." }}
          columns={[
            { title: "App", render: (_, p) => <Link to={`/apps/${p.appId}`}>{appName(p.appId)}</Link> },
            { title: "In-app muted", render: (_, p) => (p.muteInApp ? "muted" : "-") },
            { title: "SMS muted", render: (_, p) => (p.muteSms ? "muted" : "-") },
            { title: "Email muted", render: (_, p) => (p.muteEmail ? "muted" : "-") },
            { title: "Updated", render: (_, p) => <Time value={p.updatedAt} /> },
          ]}
        />
      </Card>

      <Card size="small" title="Extra data (free-form JSON the apps and the user store)" style={{ marginTop: 12 }}>
        {u.extra ? <Json value={u.extra} /> : <Typography.Text type="secondary">Hidden for your role. Keys present: {(u.extraKeys ?? []).join(", ") || "none"}.</Typography.Text>}
      </Card>

      {canPii && (
        <Card size="small" title="Audit for this user" style={{ marginTop: 12 }}>
          <AuditTable pinnedTarget={u.id} />
        </Card>
      )}
    </div>
  );
}
