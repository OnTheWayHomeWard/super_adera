package app.adera.notification.partner.delivery;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * Stops callbacks from being turned into an attack on our own network (SSRF). A partner supplies the callback address, so
 * we only ever call: https, no embedded credentials, ports 443/8443, and a host whose EVERY resolved address is a public
 * internet address (not loopback, private, link-local, cloud-metadata, carrier-grade NAT, documentation or multicast ranges).
 * Redirects are never followed (see CallbackSender). Local testing can relax the address rules with allow-private-callbacks.
 */
public final class UrlGuard {

	private UrlGuard() {
	}

	/** @return the validated URI, or throws {@link IllegalArgumentException} with a short reason code */
	public static URI check(String url, boolean allowPrivate) {
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid_url");
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equals("https") || (allowPrivate && scheme.equals("http")))) {
			throw new IllegalArgumentException("scheme_not_https");
		}
		if (uri.getHost() == null || uri.getUserInfo() != null) {
			throw new IllegalArgumentException("invalid_url");
		}
		int port = uri.getPort();
		if (!allowPrivate && port != -1 && port != 443 && port != 8443) {
			throw new IllegalArgumentException("port_not_allowed");
		}
		if (allowPrivate) {
			return uri;
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
		}
		catch (Exception e) {
			throw new IllegalArgumentException("host_not_resolvable");
		}
		for (InetAddress address : addresses) {
			if (!isPublic(address)) {
				throw new IllegalArgumentException("address_not_public");
			}
		}
		return uri;
	}

	static boolean isPublic(InetAddress a) {
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
			return false;
		}
		byte[] b = a.getAddress();
		if (a instanceof Inet4Address) {
			int b0 = b[0] & 0xFF;
			int b1 = b[1] & 0xFF;
			int b2 = b[2] & 0xFF;
			return !(b0 == 0 // "this" network
					|| (b0 == 100 && b1 >= 64 && b1 <= 127) // carrier-grade NAT
					|| (b0 == 192 && b1 == 0 && b2 == 0) // IETF protocol assignments
					|| (b0 == 192 && b1 == 0 && b2 == 2) // TEST-NET-1
					|| (b0 == 198 && (b1 == 18 || b1 == 19)) // benchmarking
					|| (b0 == 198 && b1 == 51 && b2 == 100) // TEST-NET-2
					|| (b0 == 203 && b1 == 0 && b2 == 113) // TEST-NET-3
					|| b0 >= 240); // reserved + broadcast
		}
		if (a instanceof Inet6Address) {
			int first = b[0] & 0xFE;
			return first != 0xFC; // unique local fc00::/7 (isSiteLocalAddress only covers the deprecated fec0::/10)
		}
		return true;
	}

}
