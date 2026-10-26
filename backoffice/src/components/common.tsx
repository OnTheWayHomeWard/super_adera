import { CopyOutlined } from "@ant-design/icons";
import { Alert, App as AntApp, Button, Input, Modal, Space, Tag, Tooltip, Typography } from "antd";
import type { FormInstance } from "antd";
import { useState, type ReactNode } from "react";
import { ApiError, describeError } from "../lib/errors";
import { can, type Ability } from "../lib/roles";
import { useRole } from "../lib/identity";
import { formatTime } from "../lib/format";

/** Shows an API error: the plain-language message, plus one line per field the server complained about. */
export function ErrorAlert({ error, title }: { error: unknown; title?: string }) {
  if (!error) return null;
  const lines = error instanceof ApiError ? fieldLines(error) : [];
  return (
    <Alert
      type="error"
      showIcon
      style={{ marginBottom: 12 }}
      message={title ? `${title}: ${describeError(error)}` : describeError(error)}
      description={
        lines.length || (error instanceof ApiError && error.status) ? (
          <div>
            {lines.map((l) => (
              <div key={l}>{l}</div>
            ))}
            {error instanceof ApiError && error.status ? (
              <Typography.Text type="secondary">
                HTTP {error.status} · {error.code}
              </Typography.Text>
            ) : null}
          </div>
        ) : undefined
      }
    />
  );
}

export function fieldLines(error: ApiError): string[] {
  return Object.entries(error.fields).map(([field, text]) => `${field}: ${text}`);
}

/** Puts the server's per-field messages next to the matching inputs; returns the error to show above the form. */
export function applyApiError(form: FormInstance, error: unknown, mapping: Record<string, string> = {}): void {
  if (error instanceof ApiError && error.code === "validation_failed") {
    form.setFields(Object.entries(error.fields).map(([field, text]) => ({ name: (mapping[field] ?? field).split("."), errors: [text] })));
  }
}

/** Renders children only when the signed-in role has the ability (or fallback otherwise). */
export function RoleGate({ ability, children, fallback = null }: { ability: Ability; children: ReactNode; fallback?: ReactNode }) {
  const role = useRole();
  return <>{can(role, ability) ? children : fallback}</>;
}

export function useCan(ability: Ability): boolean {
  return can(useRole(), ability);
}

const STATUS_COLOR: Record<string, string> = {
  LIVE: "green",
  ACTIVE: "green",
  GRANTED: "green",
  SENT: "green",
  VISIBLE: "green",
  REVIEWED: "green",
  DRAFT: "default",
  PAUSED: "orange",
  SUSPENDED: "orange",
  QUEUED: "blue",
  PROCESSING: "blue",
  RETRYING: "orange",
  OPEN: "red",
  KILLED: "red",
  DENIED: "red",
  FAILED: "red",
  HIDDEN: "red",
  DELETED: "red",
  DISABLED: "red",
  REVOKED: "red",
  UNREACHABLE: "volcano",
  SUPPRESSED: "default",
  EXPIRED: "default",
  DISMISSED: "default",
};

/** DEV / PROD label; with `active` it also says whether this deployment is that environment. */
export function EnvTag({ value, active }: { value: string; active?: boolean }) {
  return (
    <Space size={4}>
      <Tag color={value === "PROD" ? "purple" : "blue"} style={{ marginInlineEnd: 0 }}>{value}</Tag>
      {active === true && <Tag color="green" style={{ marginInlineEnd: 0 }}>active here</Tag>}
      {active === false && <Tag style={{ marginInlineEnd: 0 }}>not active here</Tag>}
    </Space>
  );
}

export function StatusTag({ value }: { value: string | null | undefined }) {
  if (!value) return <>-</>;
  return <Tag color={STATUS_COLOR[value] ?? "default"}>{value}</Tag>;
}

export function Time({ value }: { value: string | null | undefined }) {
  return value ? (
    <Tooltip title={value}>
      <span style={{ whiteSpace: "nowrap" }}>{formatTime(value)}</span>
    </Tooltip>
  ) : (
    <>-</>
  );
}

/** Free text of any length: wraps inside a bounded column, shows two lines and a "more" link for the rest. */
export function LongText({ value, empty = "-" }: { value: string | null | undefined; empty?: string }) {
  if (!value) return <Typography.Text type="secondary">{empty}</Typography.Text>;
  return (
    <div className="long-text">
      <Typography.Paragraph ellipsis={{ rows: 2, expandable: "collapsible", symbol: (expanded: boolean) => (expanded ? "less" : "more") }}>{value}</Typography.Paragraph>
    </div>
  );
}

export function Mono({ children }: { children: ReactNode }) {
  return <Typography.Text code>{children}</Typography.Text>;
}

export function CopyButton({ text, label = "Copy" }: { text: string; label?: string }) {
  const { message } = AntApp.useApp();
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      message.success("Copied");
    } catch {
      message.error("Could not copy: select the text and copy it by hand.");
    }
  };
  return (
    <Button size="small" icon={<CopyOutlined />} onClick={copy}>
      {label}
    </Button>
  );
}

export function Json({ value, maxHeight = 320 }: { value: unknown; maxHeight?: number }) {
  return (
    <pre style={{ margin: 0, padding: 8, background: "rgba(128,128,128,0.1)", maxHeight, overflow: "auto", fontSize: 12 }}>
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

export interface ConfirmProps {
  /** Button label. */
  label: string;
  title: string;
  description?: ReactNode;
  okText?: string;
  danger?: boolean;
  /** Ask for a written reason (shown to the server and written to the audit log). */
  reason?: { label: string; minLength: number; required: boolean };
  /** Runs when confirmed; a rejection is shown inside the dialog and keeps it open. */
  onConfirm: (reason: string) => Promise<unknown>;
  disabled?: boolean;
  size?: "small" | "middle";
  type?: "default" | "primary" | "link" | "text";
}

/** A button that asks "are you sure?" first (destructive or hard-to-undo actions). */
export function ConfirmButton(props: ConfirmProps) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<unknown>(null);
  const need = props.reason;
  const reasonTooShort = !!need && (need.required || reason.trim().length > 0) && reason.trim().length < need.minLength;

  const close = () => {
    setOpen(false);
    setReason("");
    setError(null);
  };

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      await props.onConfirm(reason.trim());
      close();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Button size={props.size ?? "small"} type={props.type} danger={props.danger} disabled={props.disabled} onClick={() => setOpen(true)}>
        {props.label}
      </Button>
      <Modal
        open={open}
        title={props.title}
        onCancel={close}
        onOk={run}
        okText={props.okText ?? props.label}
        okButtonProps={{ danger: props.danger, loading: busy, disabled: reasonTooShort }}
        cancelButtonProps={{ disabled: busy }}
        maskClosable={false}
        destroyOnHidden
      >
        {props.description ? <div style={{ marginBottom: 12 }}>{props.description}</div> : null}
        {need ? (
          <div>
            <div style={{ marginBottom: 4 }}>
              {need.label}
              {need.required ? ` (at least ${need.minLength} characters)` : ""}
            </div>
            <Input.TextArea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} showCount />
          </div>
        ) : null}
        <div style={{ marginTop: 8 }}>
          <ErrorAlert error={error} />
        </div>
      </Modal>
    </>
  );
}

export function PageHeading({ title, extra, subtitle }: { title: ReactNode; extra?: ReactNode; subtitle?: ReactNode }) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 12, gap: 12, flexWrap: "wrap" }}>
      <div>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
        {subtitle ? <Typography.Text type="secondary">{subtitle}</Typography.Text> : null}
      </div>
      <div>{extra}</div>
    </div>
  );
}
