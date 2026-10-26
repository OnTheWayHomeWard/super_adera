package app.adera.registry.common;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {

	private ClientIp() {
	}

	/** The gateway puts the real caller address in X-Client-IP; direct calls fall back to the socket address. */
	public static String of(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Client-IP");
		return forwarded != null && !forwarded.isBlank() ? forwarded.trim() : request.getRemoteAddr();
	}

}
