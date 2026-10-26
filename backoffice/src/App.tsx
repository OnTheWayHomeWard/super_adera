import {
  AuditOutlined,
  BellOutlined,
  DashboardOutlined,
  AppstoreOutlined,
  FlagOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Authenticated, Refine } from "@refinedev/core";
import { ThemedLayout, useNotificationProvider } from "@refinedev/antd";
import routerProvider, { CatchAllNavigate, DocumentTitleHandler, UnsavedChangesNotifier } from "@refinedev/react-router";
import { QueryClient } from "@tanstack/react-query";
import { App as AntApp, ConfigProvider } from "antd";
import { useEffect } from "react";
import { BrowserRouter, Navigate, Outlet, Route, Routes, useNavigate } from "react-router";
import { accessControlProvider } from "./auth/accessControl";
import { authProvider } from "./auth/authProvider";
import { setLoginNotice } from "./auth/notice";
import { AppHeader } from "./components/AppHeader";
import { dataProvider } from "./dataProvider";
import { onAuthEvent } from "./lib/client";
import { setIdentity } from "./lib/identity";
import { clearSession, getSession, saveSession } from "./lib/session";
import { AppCreatePage } from "./pages/apps/AppCreate";
import { AppListPage } from "./pages/apps/AppList";
import { AppShowPage } from "./pages/apps/AppShow";
import { AuditPage } from "./pages/Audit";
import { DashboardPage } from "./pages/Dashboard";
import { LoginPage } from "./pages/Login";
import { ModerationPage } from "./pages/Moderation";
import { NotificationShowPage } from "./pages/notifications/NotificationShow";
import { NotificationsPage } from "./pages/notifications/Notifications";
import { StaffPage } from "./pages/Staff";
import { SystemPage } from "./pages/System";
import { UserShowPage } from "./pages/users/UserShow";
import { UsersPage } from "./pages/users/Users";

const queryClient = new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false, retry: false } } });

/** Reacts to "your session is no good" answers from any API call: back to the right sign-in step. */
function AuthEvents() {
  const navigate = useNavigate();
  useEffect(
    () =>
      onAuthEvent((event) => {
        setIdentity(null);
        if (event.type === "unauthorized") {
          setLoginNotice({ kind: "info", text: "Your session ended. Sign in again." });
        } else if (event.type === "not_staff") {
          setLoginNotice({ kind: "error", text: "This account is not an active staff member of the dashboard." });
        } else {
          const session = getSession();
          if (session) saveSession({ ...session, aal: "aal1" });
          setLoginNotice({ kind: "warning", text: "The authenticator-app step is required. Enter your authenticator code." });
        }
        if (event.type !== "mfa_required") clearSession();
        navigate("/login", { replace: true });
      }),
    [navigate],
  );
  return null;
}

const icon = (node: React.ReactNode) => node;

export function App() {
  return (
    <BrowserRouter basename="/backoffice">
      <ConfigProvider>
        <AntApp>
          <Refine
            dataProvider={dataProvider}
            authProvider={authProvider}
            accessControlProvider={accessControlProvider}
            routerProvider={routerProvider}
            notificationProvider={useNotificationProvider}
            options={{ syncWithLocation: false, warnWhenUnsavedChanges: false, disableTelemetry: true, reactQuery: { clientConfig: queryClient } }}
            resources={[
              { name: "dashboard", list: "/", meta: { label: "Dashboard", icon: icon(<DashboardOutlined />) } },
              { name: "apps", list: "/apps", create: "/apps/create", show: "/apps/:id", meta: { label: "Mini apps", icon: icon(<AppstoreOutlined />) } },
              { name: "moderation", list: "/moderation", meta: { label: "Moderation", icon: icon(<FlagOutlined />) } },
              { name: "users", list: "/users", show: "/users/:id", meta: { label: "Users", icon: icon(<UserOutlined />) } },
              { name: "notifications", list: "/notifications", show: "/notifications/:id", meta: { label: "Notifications", icon: icon(<BellOutlined />) } },
              { name: "staff", list: "/staff", meta: { label: "Staff", icon: icon(<TeamOutlined />) } },
              { name: "audit", list: "/audit", meta: { label: "Audit log", icon: icon(<AuditOutlined />) } },
              { name: "system", list: "/system", meta: { label: "System", icon: icon(<SettingOutlined />) } },
              { name: "reviews", meta: { hide: true } },
              { name: "reports", meta: { hide: true } },
              { name: "templates", meta: { hide: true } },
            ]}
          >
            <AuthEvents />
            <Routes>
              <Route
                element={
                  <Authenticated key="protected" fallback={<CatchAllNavigate to="/login" />}>
                    <ThemedLayout Header={AppHeader} Title={({ collapsed }) => <strong>{collapsed ? "A" : "Adera Backoffice"}</strong>}>
                      <Outlet />
                    </ThemedLayout>
                  </Authenticated>
                }
              >
                <Route index element={<DashboardPage />} />
                <Route path="/apps">
                  <Route index element={<AppListPage />} />
                  <Route path="create" element={<AppCreatePage />} />
                  <Route path=":id" element={<AppShowPage />} />
                </Route>
                <Route path="/moderation" element={<ModerationPage />} />
                <Route path="/users">
                  <Route index element={<UsersPage />} />
                  <Route path=":id" element={<UserShowPage />} />
                </Route>
                <Route path="/notifications">
                  <Route index element={<NotificationsPage />} />
                  <Route path=":id" element={<NotificationShowPage />} />
                </Route>
                <Route path="/staff" element={<StaffPage />} />
                <Route path="/audit" element={<AuditPage />} />
                <Route path="/system" element={<SystemPage />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Route>
              <Route path="/login" element={<LoginPage />} />
            </Routes>
            <UnsavedChangesNotifier />
            <DocumentTitleHandler handler={() => "Adera Backoffice"} />
          </Refine>
        </AntApp>
      </ConfigProvider>
    </BrowserRouter>
  );
}
