/** Runtime settings, loaded from /backoffice/config.json (written at container start from environment variables). */
export interface RuntimeConfig {
  /** Show development hints (for example "the email code is in Mailpit"). Must be false in production. */
  devHints: boolean;
  mailpitUrl?: string;
  grafanaUrl?: string;
  prometheusUrl?: string;
  infisicalUrl?: string;
  rabbitmqUrl?: string;
  rustfsConsoleUrl?: string;
}

const defaults: RuntimeConfig = { devHints: false };
let current: RuntimeConfig = defaults;

export function getConfig(): RuntimeConfig {
  return current;
}

export function setConfig(value: Partial<RuntimeConfig>): void {
  current = { ...defaults, ...value };
}

export async function loadConfig(fetcher: typeof fetch = fetch): Promise<RuntimeConfig> {
  try {
    const response = await fetcher(`${import.meta.env.BASE_URL}config.json`, { cache: "no-store" });
    if (response.ok) {
      setConfig((await response.json()) as Partial<RuntimeConfig>);
    }
  } catch {
    // no config file: keep the safe defaults (no dev hints, no links)
  }
  return current;
}
