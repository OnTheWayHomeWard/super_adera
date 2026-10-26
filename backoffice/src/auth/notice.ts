/** A one-line message for the login screen ("Your session ended", "This account is not staff"). Read once. */
export interface LoginNotice {
  kind: "info" | "warning" | "error";
  text: string;
}

let pending: LoginNotice | null = null;

export function setLoginNotice(notice: LoginNotice): void {
  pending = notice;
}

export function consumeLoginNotice(): LoginNotice | null {
  const value = pending;
  pending = null;
  return value;
}
