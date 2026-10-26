import { useCreate } from "@refinedev/core";
import { Alert, Card } from "antd";
import { useNavigate } from "react-router";
import { PageHeading, useCan } from "../../components/common";
import type { AppView } from "../../lib/types";
import { AppForm } from "./AppForm";
import { emptyAppForm } from "./appForm";

export function AppCreatePage() {
  const navigate = useNavigate();
  const canWrite = useCan("write");
  const { mutateAsync } = useCreate<AppView>();
  return (
    <div>
      <PageHeading title="New mini app" subtitle="Starts as DRAFT. Add a logo and support email, then set it LIVE." />
      {!canWrite && <Alert type="warning" showIcon message="Your role is read-only: you cannot create apps." style={{ marginBottom: 12 }} />}
      <Card>
        <AppForm
          initial={emptyAppForm}
          submitLabel="Create app"
          disabled={!canWrite}
          onCancel={() => navigate("/apps")}
          onSubmit={async (payload) => {
            const result = await mutateAsync({ resource: "apps", values: payload, successNotification: false, errorNotification: false });
            navigate(`/apps/${result.data.id}`);
          }}
        />
      </Card>
    </div>
  );
}
