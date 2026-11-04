/** What GET /admin/api/v1/apps/{id}/permissions returns: per capability, how many users granted, denied or were blocked. Counts only. */
export interface CapabilityCount {
  capability: string;
  label: string;
  granted: number;
  denied: number;
  blocked: number;
}

export interface ConsentCounts {
  appId: string;
  slug: string;
  capabilities: CapabilityCount[];
}

export function countsFor(data: ConsentCounts | undefined, capability: string): CapabilityCount | undefined {
  return data?.capabilities.find((c) => c.capability === capability);
}

export function describeCounts(c: CapabilityCount | undefined): string {
  if (!c) return "-";
  if (c.granted + c.denied + c.blocked === 0) return "nobody has answered yet";
  return `${c.granted} granted, ${c.denied} denied, ${c.blocked} blocked`;
}
