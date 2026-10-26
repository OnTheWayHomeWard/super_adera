package app.adera.notification.partner.auth;

import java.net.InetAddress;
import java.util.List;

/** Does an address fall inside any of the allowed ranges ("203.0.113.10/32", "198.51.100.0/24", "2001:db8::/32")? */
public final class Cidrs {

	private Cidrs() {
	}

	public static boolean matchesAny(List<String> cidrs, String ip) {
		if (ip == null || ip.isBlank() || cidrs == null) {
			return false;
		}
		byte[] address;
		try {
			if (!ip.matches("^[0-9A-Fa-f:.]+$")) {
				return false; // a literal address only: never trigger a DNS lookup
			}
			address = InetAddress.getByName(ip.trim()).getAddress();
		}
		catch (Exception e) {
			return false;
		}
		for (String cidr : cidrs) {
			if (matches(cidr, address)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matches(String cidr, byte[] address) {
		try {
			String[] parts = cidr.split("/");
			byte[] network = InetAddress.getByName(parts[0]).getAddress();
			if (network.length != address.length) {
				return false; // IPv4 range vs IPv6 address (or the reverse)
			}
			int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
			int fullBytes = prefix / 8;
			for (int i = 0; i < fullBytes; i++) {
				if (network[i] != address[i]) {
					return false;
				}
			}
			int remaining = prefix % 8;
			if (remaining == 0) {
				return true;
			}
			int mask = 0xFF << (8 - remaining);
			return (network[fullBytes] & mask) == (address[fullBytes] & mask);
		}
		catch (Exception e) {
			return false;
		}
	}

}
