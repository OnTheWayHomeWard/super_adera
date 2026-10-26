import { AuditTable } from "../../../components/AuditTable";

export function AuditTab({ appId }: { appId: string }) {
  return <AuditTable pinnedApp={appId} />;
}
