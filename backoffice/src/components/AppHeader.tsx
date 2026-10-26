import { LogoutOutlined } from "@ant-design/icons";
import { useLogout } from "@refinedev/core";
import { Button, Layout, Space, Tag, Typography } from "antd";
import { useStaff } from "../lib/identity";
import { ROLE_LABEL } from "../lib/roles";

/** Top bar: who is signed in, with which role, and sign out. */
export function AppHeader() {
  const staff = useStaff();
  const { mutate: logout, isPending } = useLogout();
  return (
    <Layout.Header style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", padding: "0 16px", height: 48, lineHeight: "48px" }}>
      <Space>
        {staff ? (
          <>
            <Typography.Text style={{ color: "#fff" }}>{staff.email ?? staff.id}</Typography.Text>
            <Tag color={staff.role === "SUPER_ADMIN" ? "purple" : staff.role === "ADMIN" ? "blue" : "default"}>{ROLE_LABEL[staff.role]}</Tag>
          </>
        ) : null}
        <Button size="small" icon={<LogoutOutlined />} loading={isPending} onClick={() => logout()}>
          Sign out
        </Button>
      </Space>
    </Layout.Header>
  );
}
