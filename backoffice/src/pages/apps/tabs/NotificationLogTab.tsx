import { Typography } from "antd";
import { NotificationTable } from "../../../components/NotificationTable";

export function NotificationLogTab({ appId }: { appId: string }) {
  return (
    <div>
      <Typography.Paragraph type="secondary">
        Everything this app asked us to send. Open a row to see every delivery attempt and callback: the answer to "why didn't it arrive?". Message values are never shown.
      </Typography.Paragraph>
      <NotificationTable appId={appId} />
    </div>
  );
}
