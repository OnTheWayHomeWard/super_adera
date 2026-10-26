import type { AccessControlProvider } from "@refinedev/core";
import { can, type Ability } from "../lib/roles";
import { getIdentity } from "../lib/identity";

/** Which menu entries / pages each role gets. The server still decides; this only hides what would be refused. */
const RESOURCE_ABILITY: Record<string, Ability | undefined> = {
  staff: "staff.manage",
  audit: "audit.read",
};

export const accessControlProvider: AccessControlProvider = {
  can: async ({ resource }) => {
    const ability = resource ? RESOURCE_ABILITY[resource] : undefined;
    if (!ability) return { can: true };
    const role = getIdentity()?.role;
    return can(role, ability) ? { can: true } : { can: false, reason: "Your role cannot open this page." };
  },
  options: { buttons: { enableAccessControl: false, hideIfUnauthorized: false } },
};
