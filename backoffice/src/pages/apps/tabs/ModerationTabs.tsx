import { ReportsTable, ReviewsTable } from "../../../components/ModerationTables";

export const ReviewsTab = ({ appId }: { appId: string }) => <ReviewsTable appId={appId} />;
export const ReportsTab = ({ appId }: { appId: string }) => <ReportsTable appId={appId} />;
