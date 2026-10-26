import { Alert, Button, Checkbox, Form, InputNumber, Space, Switch, Typography } from "antd";
import { useState } from "react";
import { applyApiError, ErrorAlert, RoleGate, useCan } from "../../../components/common";
import { api } from "../../../lib/client";
import type { TabProps } from "./types";

interface Values {
  allowedCurrencies: string[];
  allowHold: boolean;
  minAmountMinor: number | null;
  maxAmountMinor: number | null;
}

export function PaymentTab({ detail, reload }: TabProps) {
  const canWrite = useCan("write");
  const [form] = Form.useForm<Values>();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const p = detail.payment;

  const submit = async (values: Values) => {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api(`apps/${detail.app.id}/payment-settings`, {
        method: "PUT",
        body: { allowedCurrencies: values.allowedCurrencies, allowHold: !!values.allowHold, minAmountMinor: values.minAmountMinor ?? null, maxAmountMinor: values.maxAmountMinor ?? null },
      });
      await reload();
      setSaved(true);
    } catch (e) {
      setError(e);
      applyApiError(form, e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 640 }}>
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message="Payment settings are policy only for now: the payment feature (pay.request) is not built yet. Amounts are in minor units (cents / santim): 1000 = 10.00." />
      {!p && <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="No payment settings saved yet." />}
      <ErrorAlert error={error} />
      <Form
        form={form}
        layout="vertical"
        disabled={!canWrite}
        onFinish={submit}
        initialValues={{ allowedCurrencies: p?.allowedCurrencies ?? [], allowHold: p?.allowHold ?? false, minAmountMinor: p?.minAmountMinor ?? null, maxAmountMinor: p?.maxAmountMinor ?? null }}
      >
        <Form.Item label="Allowed currencies" name="allowedCurrencies">
          <Checkbox.Group options={["ETB", "USD"]} />
        </Form.Item>
        <Form.Item label="Allow holds (reserve now, charge later)" name="allowHold" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Space size="large" align="start" wrap>
          <Form.Item label="Minimum amount (minor units)" name="minAmountMinor" extra="Empty = no minimum.">
            <InputNumber min={0} precision={0} style={{ width: 200 }} />
          </Form.Item>
          <Form.Item label="Maximum amount (minor units)" name="maxAmountMinor" extra="Empty = no maximum.">
            <InputNumber min={0} precision={0} style={{ width: 200 }} />
          </Form.Item>
        </Space>
        <RoleGate ability="write">
          <Space>
            <Button type="primary" htmlType="submit" loading={busy}>
              Save payment settings
            </Button>
            {saved && <Typography.Text type="success">Saved</Typography.Text>}
          </Space>
        </RoleGate>
      </Form>
    </div>
  );
}
