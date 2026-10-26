import type { AppDetail } from "../../../lib/types";

export interface TabProps {
  detail: AppDetail;
  /** Refetch the app after a change. */
  reload: () => Promise<unknown>;
}
