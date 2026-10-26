package app.adera.registry.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Validates and normalises IP ranges ("203.0.113.0/24", "203.0.113.10", "2001:db8::/32"). Never does a DNS lookup. */
public final class Cidrs {

	private static final Pattern V4 = Pattern.compile("^(\\d{1,3})(\\.\\d{1,3}){3}$");
	private static final Pattern V6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

	private Cidrs() {
	}

	/** @return the canonical form {@code ip/prefix}, or throws {@link IllegalArgumentException} */
	public static String normalise(String input) {
		if (input == null || input.isBlank()) {
			throw new IllegalArgumentException("empty");
		}
		String[] parts = input.trim().split("/", -1);
		if (parts.length > 2) {
			throw new IllegalArgumentException("too many '/'");
		}
		String host = parts[0];
		boolean v4 = V4.matcher(host).matches();
		boolean v6 = !v4 && host.contains(":") && V6.matcher(host).matches();
		if (!v4 && !v6) {
			throw new IllegalArgumentException("not an IP address");
		}
		InetAddress address;
		try {
			address = InetAddress.getByName(host); // a literal: no DNS involved
		}
		catch (UnknownHostException e) {
			throw new IllegalArgumentException("not an IP address");
		}
		int max = address.getAddress().length * 8;
		int prefix = max;
		if (parts.length == 2) {
			try {
				prefix = Integer.parseInt(parts[1]);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("bad prefix length");
			}
			if (prefix < 0 || prefix > max) {
				throw new IllegalArgumentException("prefix must be 0-" + max);
			}
		}
		return address.getHostAddress() + "/" + prefix;
	}

	public static boolean matchesEverything(String normalised) {
		return normalised.endsWith("/0");
	}

}
