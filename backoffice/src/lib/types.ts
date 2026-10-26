/** Shapes of the admin API answers (see docs/src/08b-staff-api.html). Only the fields the screens use. */
export type Lang = "en" | "am" | "om";
export const LANGS: Lang[] = ["en", "am", "om"];
export type I18n = Partial<Record<Lang, string>>;
export type AppStatus = "DRAFT" | "LIVE" | "PAUSED" | "KILLED";
export const APP_STATUSES: AppStatus[] = ["DRAFT", "LIVE", "PAUSED", "KILLED"];
/** An app can store settings for both; a deployment is exactly one of them (ADERA_ENV) and only that one is active there. */
export type Environment = "DEV" | "PROD";
export const ENVIRONMENTS: Environment[] = ["DEV", "PROD"];

export interface SystemInfo {
  environment: Environment;
}
export type Channel = "SMS" | "EMAIL" | "IN_APP";
export const CHANNELS: Channel[] = ["SMS", "EMAIL", "IN_APP"];

export interface Publisher {
  name: string | null;
  legalName: string | null;
  supportEmail: string | null;
  supportPhone: string | null;
  website: string | null;
}

export interface AppView {
  id: string;
  slug: string;
  name: I18n;
  description: I18n;
  logoImageId: string | null;
  logoUrl: string | null;
  brandColor: string | null;
  categories: string[];
  markets: string[];
  publisher: Publisher;
  entryUrl: string | null;
  allowedOrigins: string[];
  status: AppStatus;
  priority: number;
  ratingAvg: number;
  ratingCount: number;
  extra: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface KeyView {
  keyId: string;
  environment: Environment;
  algorithm: string;
  fingerprint: string;
  label: string | null;
  status: string;
  expiresAt: string | null;
  createdAt: string;
  revokedAt: string | null;
}

export interface CapabilityRow {
  capability: string;
  state: "GRANTED" | "DENIED" | "SUSPENDED";
  reason: I18n | null;
  updatedAt: string;
}

export interface LimitRow {
  channel: Channel;
  maxPerUserPerDay: number | null;
  maxPerDay: number | null;
  maxDistinctUsersPerDay: number | null;
  maxTotal: number | null;
}

export interface EnvironmentRow {
  environment: Environment;
  allowedCidrs: string[];
}

export interface PaymentRow {
  allowedCurrencies: string[];
  allowHold: boolean;
  minAmountMinor: number | null;
  maxAmountMinor: number | null;
}

export interface AppDetail {
  app: AppView;
  capabilities: CapabilityRow[];
  payment: PaymentRow | null;
  notificationLimits: LimitRow[];
  environments: EnvironmentRow[];
  keys: KeyView[];
}

export interface CapabilityInfo {
  key: string;
  description: string;
  reasonRequired: boolean;
}

export interface TemplateParam {
  name: string;
  maxLength: number;
  required: boolean;
  allowLinks: boolean;
}

export interface Template {
  id: string;
  appId: string;
  code: string;
  kind: "DIRECT" | "IN_APP";
  category: "TRANSACTIONAL" | "REMINDER" | "PROMOTIONAL";
  params: TemplateParam[];
  content: Partial<Record<Lang, Partial<Record<"title" | "body" | "sms" | "emailSubject" | "emailHtml", string>>>>;
  active: boolean;
  validFrom: string | null;
  expiresAt: string | null;
  version: number;
  usableNow: boolean;
  unusableReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface NotificationRow {
  id: string;
  appId?: string;
  externalId: string;
  environment: Environment;
  status: string;
  channel: string;
  reason: string | null;
  attempts: number;
  kind: string;
  createdAt: string;
  completedAt: string | null;
}

export interface NotificationDetail extends NotificationRow {
  appId: string;
  templateId: string;
  templateVersion: number;
  nextAttemptAt: string | null;
  expiresAt: string | null;
  attemptLog: { channel: string; provider: string; outcome: string; errorCode: string | null; at: string }[];
  callbacks: { status: string; attempts: number; lastHttpStatus: number | null; lastError: string | null; nextAttemptAt: string | null }[];
}

export interface UsageRow {
  channel: Channel;
  lifetime: number;
  today: number;
  distinct_users_today: number;
}

export interface Review {
  id: string;
  appId: string;
  appSlug?: string;
  userId: string;
  rating: number;
  comment: string | null;
  status: "VISIBLE" | "HIDDEN";
  createdAt: string;
  updatedAt: string;
}

export interface Report {
  id: string;
  appId: string;
  appSlug?: string;
  userId: string;
  reason: string;
  details: string | null;
  status: "OPEN" | "REVIEWED" | "DISMISSED";
  handledBy: string | null;
  handledAt: string | null;
  createdAt: string;
}

export interface AuditEvent {
  id: string;
  actorId: string | null;
  actorEmail?: string | null;
  actorRole: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  meta: Record<string, unknown>;
  ip: string | null;
  at: string;
}

export interface StaffMember {
  id: string;
  email: string;
  role: "VIEWER" | "ADMIN" | "SUPER_ADMIN";
  status: "ACTIVE" | "DISABLED";
  bootstrap?: boolean;
  createdBy?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UserSummary {
  id: string;
  email: string | null;
  phone: string | null;
  firstName: string | null;
  lastName: string | null;
  status: "ACTIVE" | "SUSPENDED" | "DELETED";
  market: string;
  language: string;
  verificationTier: string;
  createdAt: string;
  lastSeenAt: string | null;
}

export interface UserDetail extends UserSummary {
  masked: boolean;
  gender: string | null;
  dateOfBirth: string | null;
  timezone: string;
  avatarImageId: string | null;
  location: { countryCode: string | null; region: string | null; city: string | null; hasPreciseLocation: boolean; updatedAt: string | null } | null;
  termsAcceptedVersion: string | null;
  termsAcceptedAt: string | null;
  deletedAt: string | null;
  updatedAt: string;
  extra: Record<string, unknown> | null;
  extraKeys?: string[];
  devices: { active: number; total: number; platforms: string[] };
  aliasCount: number;
  mutePreferences: { appId: string; muteInApp: boolean; muteSms: boolean; muteEmail: boolean; updatedAt: string | null }[];
}

export interface DashboardData {
  apps: Record<AppStatus, number> & { total: number };
  reports: { open: number; total: number };
  reviews: { total: number; hidden: number; last7Days: number };
  staff: { active: number; disabled: number };
  draftApps: { id: string; slug: string; name: I18n; missingForLive: string[] }[];
}

export interface NotificationStats {
  total: number;
  last24h: { total: number; byStatus: Record<string, number> };
  byStatus: Record<string, number>;
  byChannel: Record<string, number>;
}

export interface UserStats {
  total: number;
  active: number;
  suspended: number;
  deleted: number;
  createdLast7Days: number;
}
