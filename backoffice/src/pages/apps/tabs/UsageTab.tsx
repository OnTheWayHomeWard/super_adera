import { Alert, Progress, Radio, Space, Table, Typography } from "antd";
import { useState } from "react";
import { ErrorAlert } from "../../../components/common";
import { limitText } from "../../../lib/format";
import { useApiQuery, useDeploymentEnvironment } from "../../../lib/hooks";
import { CHANNELS, ENVIRONMENTS, type Channel, type Environment, type LimitRow, type UsageRow } from "../../../lib/types";
import type { TabProps } from "./types";

/** Percent of a limit used. null limit = unlimited (no bar); 0 limit with any use = 100. */
export function usagePercent(used: number, limit: number | null | undefined): number | null {
  if (limit === null || limit === undefined) return null;
  if (limit === 0) return used > 0 ? 100 : 0;
  return Math.min(100, Math.round((used / limit) * 100));
}

function Meter({ used, limit }: { used: number; limit: number | null | undefined }) {
  const pct = usagePercent(used, limit);
  return (
    <div style={{ minWidth: 190 }}>
      <div>
        {used} / {limitText(limit)}
      </div>
      {pct !== null && <Progress percent={pct} size="small" status={pct >= 100 ? "exception" : pct >= 80 ? "active" : "normal"} showInfo={false} />}
    </div>
  );
}

export function UsageTab({ detail }: Pick<TabProps, "detail">) {
  const deployment = useDeploymentEnvironment();
  const [chosen, setChosen] = useState<Environment | null>(null);
  const env: Environment = chosen ?? deployment ?? "DEV"; // defaults to the environment this deployment runs
  const usage = useApiQuery<UsageRow[]>(["usage", detail.app.id, env], "notification/usage", { appId: detail.app.id, environment: env });
  const limits = new Map<Channel, LimitRow>(detail.notificationLimits.map((l) => [l.channel, l]));
  const rows = new Map<string, UsageRow>((usage.data ?? []).map((u) => [u.channel, u]));

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Radio.Group value={env} onChange={(e) => setChosen(e.target.value)} options={ENVIRONMENTS.map((e) => ({ value: e, label: deployment === e ? `${e} (active here)` : e }))} optionType="button" />
        <Typography.Text type="secondary">Today means the current UTC day. Usage is counted per environment; limits apply to each.</Typography.Text>
      </Space>
      <ErrorAlert error={usage.error} />
      {usage.data && usage.data.length === 0 && <Alert type="info" showIcon style={{ marginBottom: 12 }} message={`Nothing has been sent in ${env} yet.`} />}
      <Table<Channel>
        size="small"
        rowKey={(c) => c}
        loading={usage.isLoading}
        pagination={false}
        dataSource={CHANNELS}
        columns={[
          { title: "Channel", render: (_, c) => <b>{c}</b> },
          { title: "Sent today vs max per day", render: (_, c) => <Meter used={rows.get(c)?.today ?? 0} limit={limits.get(c)?.maxPerDay} /> },
          { title: "Users reached today vs max distinct users", render: (_, c) => <Meter used={rows.get(c)?.distinctUsersToday ?? 0} limit={limits.get(c)?.maxDistinctUsersPerDay} /> },
          { title: "Lifetime vs max total", render: (_, c) => <Meter used={rows.get(c)?.lifetime ?? 0} limit={limits.get(c)?.maxTotal} /> },
          { title: "Per-user daily cap", render: (_, c) => limitText(limits.get(c)?.maxPerUserPerDay) },
        ]}
      />
    </div>
  );
}
