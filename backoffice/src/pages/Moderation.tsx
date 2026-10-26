import { Tabs, Typography } from "antd";
import { useSearchParams } from "react-router";
import { ReportsTable, ReviewsTable } from "../components/ModerationTables";
import { PageHeading } from "../components/common";

/** Reviews and reports across all apps. (There is no review approval queue: reviews are visible until hidden.) */
export function ModerationPage() {
  const [params, setParams] = useSearchParams();
  return (
    <div>
      <PageHeading title="Moderation" subtitle="Reviews and user reports across all mini apps" />
      <Typography.Paragraph type="secondary">Reviews are published immediately; hide the ones that break the rules. Reports are filed by users against an app.</Typography.Paragraph>
      <Tabs
        activeKey={params.get("tab") ?? "reports"}
        onChange={(k) => setParams({ tab: k }, { replace: true })}
        destroyOnHidden
        items={[
          { key: "reports", label: "Reports", children: <ReportsTable initialStatus="OPEN" /> },
          { key: "reviews", label: "Reviews", children: <ReviewsTable /> },
        ]}
      />
    </div>
  );
}
