import { PlusOutlined } from "@ant-design/icons";
import { Alert, Button, Form, Input, Modal, Select, Space, Table, Tag, Typography } from "antd";
import { useState } from "react";
import { applyApiError, ConfirmButton, ErrorAlert, PageHeading, RoleGate, StatusTag, Time, useCan } from "../components/common";
import { api } from "../lib/client";
import { useApiQuery, useRefreshKeys } from "../lib/hooks";
import { useStaff } from "../lib/identity";
import { ROLE_LABEL, type Role } from "../lib/roles";
import type { StaffMember } from "../lib/types";

const ROLES: Role[] = ["VIEWER", "ADMIN", "SUPER_ADMIN"];
const ROLE_HELP = "VIEWER reads everything but cannot change anything and sees masked personal data. ADMIN changes apps, templates, keys, moderation and user status. SUPER_ADMIN also manages staff.";

export function StaffPage() {
  const allowed = useCan("staff.manage");
  const me = useStaff();
  const refresh = useRefreshKeys();
  const staff = useApiQuery<StaffMember[]>(["staff"], "staff", undefined, { enabled: allowed });
  const [adding, setAdding] = useState(false);
  const [changing, setChanging] = useState<StaffMember | null>(null);

  if (!allowed) return <Alert type="warning" showIcon message="Staff management is for super admins." />;

  const update = async (m: StaffMember, body: Record<string, unknown>) => {
    await api(`staff/${m.id}`, { method: "PATCH", body });
    await refresh(["staff"]);
  };

  return (
    <div>
      <PageHeading
        title="Staff"
        subtitle="People who can sign in to this dashboard"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setAdding(true)}>
            Add staff member
          </Button>
        }
      />
      <Typography.Paragraph type="secondary">{ROLE_HELP}</Typography.Paragraph>
      <ErrorAlert error={staff.error} />
      <Table<StaffMember>
        size="small"
        rowKey="id"
        loading={staff.isLoading}
        pagination={false}
        scroll={{ x: "max-content" }}
        dataSource={staff.data ?? []}
        columns={[
          {
            title: "Email",
            render: (_, m) => (
              <Space>
                {m.email}
                {m.id === me?.id && <Tag color="blue">you</Tag>}
                {m.bootstrap && <Tag color="gold">bootstrap super admin</Tag>}
                {m.createdBy === null && !m.bootstrap && <Tag>created by bootstrap</Tag>}
              </Space>
            ),
          },
          { title: "Role", render: (_, m) => ROLE_LABEL[m.role] },
          { title: "Status", render: (_, m) => <StatusTag value={m.status} /> },
          { title: "Added", render: (_, m) => <Time value={m.createdAt} /> },
          { title: "Updated", render: (_, m) => <Time value={m.updatedAt} /> },
          {
            title: "",
            render: (_, m) => (
              <RoleGate ability="staff.manage">
                <Space>
                  <Button size="small" onClick={() => setChanging(m)}>
                    Change role
                  </Button>
                  {m.status === "ACTIVE" ? (
                    <ConfirmButton
                      label="Deactivate"
                      danger
                      title={`Deactivate ${m.email}?`}
                      description="Their dashboard sessions are revoked and they can no longer sign in. The server refuses if this is the last active super admin, or yourself."
                      onConfirm={() => update(m, { status: "DISABLED" })}
                    />
                  ) : (
                    <ConfirmButton label="Reactivate" title={`Reactivate ${m.email}?`} description={`They can sign in again with the role ${m.role}.`} onConfirm={() => update(m, { status: "ACTIVE" })} />
                  )}
                </Space>
              </RoleGate>
            ),
          },
        ]}
      />
      {adding && <AddModal onClose={() => setAdding(false)} onSaved={() => refresh(["staff"])} />}
      {changing && <RoleModal member={changing} onClose={() => setChanging(null)} onSave={(role) => update(changing, { role })} />}
    </div>
  );
}

function AddModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => Promise<unknown> }) {
  const [form] = Form.useForm<{ email: string; role: Role }>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (v: { email: string; role: Role }) => {
    setBusy(true);
    setError(null);
    try {
      await api("staff", { method: "POST", body: { email: v.email.trim().toLowerCase(), role: v.role } });
      await onSaved();
      onClose();
    } catch (e) {
      setError(e);
      applyApiError(form, e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open title="Add a staff member" onCancel={onClose} onOk={() => form.submit()} okText="Add" confirmLoading={busy} maskClosable={false}>
      <Typography.Paragraph type="secondary">
        They sign in with an emailed code, then set up an authenticator app on their first visit. If the email already has an account it is granted the role.
      </Typography.Paragraph>
      <ErrorAlert error={error} />
      <Form form={form} layout="vertical" onFinish={submit} initialValues={{ role: "VIEWER" }}>
        <Form.Item label="Email" name="email" rules={[{ required: true, message: "Enter an email address" }, { type: "email", message: "Not a valid email address" }]}>
          <Input autoFocus />
        </Form.Item>
        <Form.Item label="Role" name="role" extra={ROLE_HELP}>
          <Select options={ROLES.map((r) => ({ value: r, label: ROLE_LABEL[r] }))} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function RoleModal({ member, onClose, onSave }: { member: StaffMember; onClose: () => void; onSave: (role: Role) => Promise<unknown> }) {
  const [role, setRole] = useState<Role>(member.role);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  return (
    <Modal
      open
      title={`Change role of ${member.email}`}
      onCancel={onClose}
      okText="Save role"
      okButtonProps={{ disabled: role === member.role, loading: busy }}
      onOk={async () => {
        setBusy(true);
        setError(null);
        try {
          await onSave(role);
          onClose();
        } catch (e) {
          setError(e);
        } finally {
          setBusy(false);
        }
      }}
      maskClosable={false}
    >
      <ErrorAlert error={error} />
      <Select style={{ width: "100%" }} value={role} onChange={setRole} options={ROLES.map((r) => ({ value: r, label: ROLE_LABEL[r] }))} />
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
        {ROLE_HELP}
      </Typography.Paragraph>
    </Modal>
  );
}
