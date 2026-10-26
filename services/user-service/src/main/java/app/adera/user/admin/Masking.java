package app.adera.user.admin;

/** Partial hiding of contact details for staff who do not need the full value (VIEWER). */
public final class Masking {

	private Masking() {
	}

	/** alice@gmail.com becomes a***@g***.com: first character, and the last part of the domain. */
	public static String email(String email) {
		if (email == null) {
			return null;
		}
		int at = email.lastIndexOf('@');
		if (at < 1 || at == email.length() - 1) {
			return "***";
		}
		String domain = email.substring(at + 1);
		int dot = domain.lastIndexOf('.');
		String tld = dot > 0 ? domain.substring(dot) : "";
		return email.charAt(0) + "***@" + domain.charAt(0) + "***" + tld;
	}

	/** +251911234567 becomes +251*****4567: the country part and the last four digits. */
	public static String phone(String phone) {
		if (phone == null) {
			return null;
		}
		int keepStart = phone.startsWith("+1") ? 2 : 4;
		if (phone.length() < keepStart + 4 + 2) {
			return "***" + phone.substring(Math.max(0, phone.length() - 2));
		}
		return phone.substring(0, keepStart) + "*".repeat(phone.length() - keepStart - 4) + phone.substring(phone.length() - 4);
	}

}
