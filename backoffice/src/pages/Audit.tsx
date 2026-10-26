import { AuditTable } from "../components/AuditTable";
import { PageHeading } from "../components/common";
import { useApiQuery } from "../lib/hooks";
import { useCan } from "../components/common";
import type { StaffMember } from "../lib/types";

export function AuditPage() {
  const isSuper = useCan("staff.manage");
  // only super admins may list staff; for them the actor filter becomes a name picker
  const staff = useApiQuery<StaffMember[]>(["staff"], "staff", undefined, { enabled: isSuper });
  return (
    <div>
      <PageHeading title="Audit log" subtitle="Who changed what, when and from where. Newest first. Append-only." />
      <AuditTable staff={staff.data?.map((s) => ({ id: s.id, email: s.email }))} />
    </div>
  );
}
