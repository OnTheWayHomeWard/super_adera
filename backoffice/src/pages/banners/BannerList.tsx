import { PlusOutlined } from "@ant-design/icons";
import { Alert, Button, Image, Select, Space, Table, Tabs, Tag, Tooltip, Typography } from "antd";
import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { ConfirmButton, ErrorAlert, PageHeading, RoleGate, Time } from "../../components/common";
import {
  ACTION_LABEL,
  CATEGORIES,
  CATEGORY_LABEL,
  hiddenReason,
  PLACEMENT_LABEL,
  PLACEMENTS,
  STATE_COLOR,
  STATE_HELP,
  STATE_LABEL,
  STATE_TABS,
  stateFilter,
  statusActions,
  type BannerView,
} from "../../lib/banners";
import { api } from "../../lib/client";
import { i18nText } from "../../lib/format";
import { useApiList, useRefreshKeys } from "../../lib/hooks";

const PAGE_SIZE = 25;

export function StateBadge({ banner }: { banner: Pick<BannerView, "state" | "visible" | "app" | "imageId"> }) {
  const reason = hiddenReason(banner);
  return (
    <Space size={4}>
      <Tooltip title={STATE_HELP[banner.state]}>
        <Tag color={STATE_COLOR[banner.state]} style={{ marginInlineEnd: 0 }}>
          {STATE_LABEL[banner.state]}
        </Tag>
      </Tooltip>
      {reason && (
        <Tooltip title={reason}>
          <Tag color="volcano" style={{ marginInlineEnd: 0 }}>
            not shown
          </Tag>
        </Tooltip>
      )}
    </Space>
  );
}

/** Publish / Pause / Back to draft, as fits the current status (ADMIN and up). */
export function StatusButtons({ banner, onChanged, size = "small" }: { banner: BannerView; onChanged: () => Promise<unknown>; size?: "small" | "middle" }) {
  const actions = statusActions(banner);
  const [error, setError] = useState<unknown>(null);
  const set = async (status: string) => {
    setError(null);
    try {
      await api(`banners/${banner.id}/status`, { method: "POST", body: { status } });
      await onChanged();
    } catch (e) {
      setError(e);
    }
  };
  return (
    <RoleGate ability="write">
      <Space size={4} wrap>
        {actions.publish && (
          <Tooltip title={actions.publishBlocked ?? ""}>
            <Button size={size} type="primary" disabled={!!actions.publishBlocked} onClick={() => set("PUBLISHED")}>
              Publish
            </Button>
          </Tooltip>
        )}
        {actions.pause && (
          <Button size={size} onClick={() => set("PAUSED")}>
            Pause
          </Button>
        )}
        {actions.draft && (
          <Button size={size} onClick={() => set("DRAFT")}>
            Back to draft
          </Button>
        )}
      </Space>
      <ErrorAlert error={error} />
    </RoleGate>
  );
}

export function BannerListPage() {
  const navigate = useNavigate();
  const refresh = useRefreshKeys();
  const [params, setParams] = useSearchParams();
  const tab = params.get("state") ?? "LIVE";
  const placement = params.get("placement") ?? undefined;
  const category = params.get("category") ?? undefined;
  const [page, setPage] = useState(1);
  const query = { state: stateFilter(tab), placement, category, page: page - 1, size: PAGE_SIZE };
  const list = useApiList<BannerView>(["banners", query], "banners", query);

  const setParam = (key: string, value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next);
    setPage(1);
  };
  const reload = () => refresh(["banners"]);

  return (
    <div>
      <PageHeading
        title="Banners"
        subtitle="Promotional banners in the super app. Highest priority first, then newest. The state is computed from the status and the clock."
        extra={
          <RoleGate ability="write">
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate("/banners/create")}>
              New banner
            </Button>
          </RoleGate>
        }
      />
      <Tabs activeKey={tab} onChange={(k) => setParam("state", k)} items={STATE_TABS.map((t) => ({ key: t.key, label: t.label }))} />
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="Any placement"
          style={{ width: 280 }}
          value={placement}
          options={PLACEMENTS.map((p) => ({ value: p, label: PLACEMENT_LABEL[p] }))}
          onChange={(v) => setParam("placement", v)}
        />
        <Select
          allowClear
          placeholder="Any category"
          style={{ width: 180 }}
          value={category}
          options={CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))}
          onChange={(v) => setParam("category", v)}
        />
        <Typography.Text type="secondary">{list.data?.total ?? 0} banners</Typography.Text>
      </Space>
      {tab === "LIVE" && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="Live = published and inside its time window. A live banner of a mini app that is not LIVE is marked 'not shown'."
        />
      )}
      <ErrorAlert error={list.error} />
      <Table<BannerView>
        rowKey="id"
        size="small"
        loading={list.isLoading}
        dataSource={list.data?.rows ?? []}
        scroll={{ x: "max-content" }}
        pagination={{ current: page, pageSize: PAGE_SIZE, total: list.data?.total ?? 0, onChange: setPage, hideOnSinglePage: true, showSizeChanger: false }}
        onRow={(b) => ({ onDoubleClick: () => navigate(`/banners/${b.id}`) })}
        columns={[
          {
            title: "",
            width: 112,
            render: (_, b) =>
              b.images?.small ? <Image src={b.images.small.url} width={96} alt="" preview={{ src: b.images.large?.url }} /> : <Typography.Text type="secondary">no image</Typography.Text>,
          },
          { title: "Title", render: (_, b) => <Link to={`/banners/${b.id}`}>{i18nText(b.title)}</Link> },
          { title: "Placement", dataIndex: "placement" },
          { title: "Category", render: (_, b) => CATEGORY_LABEL[b.category] },
          { title: "Linked app", render: (_, b) => (b.app ? <Link to={`/apps/${b.app.id}`}>{i18nText(b.app.name) || b.app.slug}</Link> : "-") },
          { title: "On tap", render: (_, b) => <Tooltip title={b.target ?? ""}>{ACTION_LABEL[b.actionType]}</Tooltip> },
          { title: "Starts", render: (_, b) => <Time value={b.startsAt} /> },
          { title: "Ends", render: (_, b) => (b.endsAt ? <Time value={b.endsAt} /> : <Typography.Text type="secondary">open-ended</Typography.Text>) },
          { title: "Priority", dataIndex: "priority" },
          { title: "State", render: (_, b) => <StateBadge banner={b} /> },
          {
            title: "",
            render: (_, b) => (
              <Space size={4}>
                <StatusButtons banner={b} onChanged={reload} />
                <RoleGate ability="write">
                  <ConfirmButton
                    label="Delete"
                    danger
                    title="Delete this banner?"
                    description="The banner and its image are deleted for good. Pausing it is the reversible alternative."
                    onConfirm={async () => {
                      await api(`banners/${b.id}`, { method: "DELETE" });
                      await reload();
                    }}
                  />
                </RoleGate>
              </Space>
            ),
          },
        ]}
      />
    </div>
  );
}
