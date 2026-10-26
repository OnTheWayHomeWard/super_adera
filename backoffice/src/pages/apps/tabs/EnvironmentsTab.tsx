import { Alert, Button, Card, Col, Form, Input, App as AntApp, Row, Space, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { ErrorAlert, RoleGate, useCan } from "../../../components/common";
import { api } from "../../../lib/client";
import { splitList } from "../../../lib/format";
import { useDeploymentEnvironment } from "../../../lib/hooks";
import { ENVIRONMENTS, type Environment, type EnvironmentRow } from "../../../lib/types";
import type { TabProps } from "./types";

const HELP: Record<Environment, string> = {
  DEV: "Used by the dev deployment. Works for any app that is not KILLED, so a partner can integrate before going live.",
  PROD: "Used by the prod deployment. Works only while the app status is LIVE. A range that matches every address (/0) is not allowed here.",
};

export function EnvironmentsTab({ detail, reload }: TabProps) {
  const active = useDeploymentEnvironment();
  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="Allowed server addresses"
        description={
          <>
            A partner's server may only call the API from these IP addresses or ranges (CIDR, like 203.0.113.0/24). A new app starts with an empty list in both environments, which means nothing can call yet.
            {" "}A deployment is exactly one environment: you can fill in both lists, but only the one matching this deployment is used here.
          </>
        }
      />
      {active && (
        <Alert type="success" showIcon style={{ marginBottom: 12 }} message={`This deployment runs ${active}.`} description={`The ${ENVIRONMENTS.find((e) => e !== active)} list below is stored for the other deployment and is not used here.`} />
      )}
      <Row gutter={12}>
        {ENVIRONMENTS.map((env) => (
          <Col xs={24} lg={12} key={env}>
            <EnvironmentCard appId={detail.app.id} env={env} active={active === undefined ? undefined : active === env} row={detail.environments.find((e) => e.environment === env)} onSaved={reload} />
          </Col>
        ))}
      </Row>
    </div>
  );
}

function EnvironmentCard({ appId, env, active, row, onSaved }: { appId: string; env: Environment; active?: boolean; row?: EnvironmentRow; onSaved: () => Promise<unknown> }) {
  const canWrite = useCan("write");
  const { message } = AntApp.useApp();
  const [text, setText] = useState((row?.allowedCidrs ?? []).join("\n"));
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  // The server may normalise what was typed (203.0.113.7 is stored as 203.0.113.7/32): show what is really saved.
  const savedKey = (row?.allowedCidrs ?? []).join("\n");
  useEffect(() => setText(savedKey), [savedKey]);
  const list = splitList(text);
  const changed = JSON.stringify(list) !== JSON.stringify(row?.allowedCidrs ?? []);

  const save = async () => {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api(`apps/${appId}/environments/${env}`, { method: "PUT", body: { allowedCidrs: list } });
      await onSaved();
      setSaved(true);
      message.success(`${env} addresses saved`);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card size="small" data-testid={`env-card-${env}`} title={<Space>{env}{active === true && <Tag color="green">ACTIVE on this deployment</Tag>}{active === false && <Tag>stored, not used here</Tag>}<Tag>{(row?.allowedCidrs ?? []).length === 1 ? "1 address" : `${(row?.allowedCidrs ?? []).length} addresses`}</Tag></Space>} style={{ marginBottom: 12 }}>
      <Typography.Paragraph type="secondary">{HELP[env]}</Typography.Paragraph>
      {active === false && <Alert type="info" showIcon style={{ marginBottom: 8 }} message={`This deployment is not ${env}: these addresses are stored but unused here. They apply on the ${env} deployment.`} />}
      {(row?.allowedCidrs ?? []).length === 0 && <Alert type="warning" showIcon style={{ marginBottom: 8 }} message={active === false ? "Empty: nothing can call in this environment once a deployment runs it." : "Empty: no server can call in this environment."} />}
      <ErrorAlert error={error} />
      <Form layout="vertical">
        <Form.Item label="One address or range per line (or comma separated), at most 50" extra="IPv4 or IPv6; a single address is fine.">
          <Input.TextArea rows={6} value={text} onChange={(e) => setText(e.target.value)} disabled={!canWrite} spellCheck={false} style={{ fontFamily: "monospace" }} />
        </Form.Item>
      </Form>
      <RoleGate ability="write">
        <Space>
          <Button type="primary" onClick={save} loading={busy} disabled={!changed}>
            Save addresses
          </Button>
          {changed && <Button onClick={() => setText((row?.allowedCidrs ?? []).join("\n"))}>Reset</Button>}
          {saved && !changed && <Typography.Text type="success">Saved</Typography.Text>}
        </Space>
      </RoleGate>
    </Card>
  );
}
