package app.adera.registry.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import app.adera.registry.config.RegistryProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Decides who may call what, by path:
 * <ul>
 * <li>{@code /admin/api/**}: via the gateway (shared secret) with a verified staff id and role;</li>
 * <li>{@code /api/**}: via the gateway with a verified user id;</li>
 * <li>{@code /internal/staff/verify}: via the gateway;</li>
 * <li>{@code /internal/apps/**}, {@code /internal/partner-keys/**}: other services, with the internal secret.</li>
 * </ul>
 * An empty secret disables its check (local dev only; a warning is logged at startup).
 */
@Component
public class TrustFilter extends OncePerRequestFilter {

	public static final String GATEWAY_SECRET = "X-Gateway-Secret";
	public static final String INTERNAL_SECRET = "X-Internal-Secret";

	private final byte[] gatewaySecret;
	private final byte[] internalSecret;

	public TrustFilter(RegistryProperties props) {
		this.gatewaySecret = props.gatewaySecret().getBytes(StandardCharsets.UTF_8);
		this.internalSecret = props.internalSecret().getBytes(StandardCharsets.UTF_8);
		if (gatewaySecret.length == 0 || internalSecret.length == 0) {
			logger.warn("GATEWAY_SECRET / INTERNAL_API_SECRET not set: trust checks are disabled (dev only!)");
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		if (path.startsWith("/admin/api/")) {
			if (!secretOk(request, GATEWAY_SECRET, gatewaySecret) || !staffHeadersOk(request)) {
				reject(response);
				return;
			}
		}
		else if (path.startsWith("/api/")) {
			if (!secretOk(request, GATEWAY_SECRET, gatewaySecret) || !uuid(request.getHeader("X-User-Id"))) {
				reject(response);
				return;
			}
		}
		else if (path.equals("/internal/staff/verify")) {
			if (!secretOk(request, GATEWAY_SECRET, gatewaySecret)) {
				reject(response);
				return;
			}
		}
		else if (path.startsWith("/internal/")) {
			if (!secretOk(request, INTERNAL_SECRET, internalSecret)) {
				reject(response);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private static boolean staffHeadersOk(HttpServletRequest request) {
		return uuid(request.getHeader("X-Staff-Id")) && Role.parseOrNull(request.getHeader("X-Staff-Role")) != null;
	}

	private static boolean uuid(String value) {
		try {
			UUID.fromString(String.valueOf(value));
			return true;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static boolean secretOk(HttpServletRequest request, String header, byte[] expected) {
		if (expected.length == 0) {
			return true;
		}
		String sent = request.getHeader(header);
		return sent != null && MessageDigest.isEqual(expected, sent.getBytes(StandardCharsets.UTF_8));
	}

	private static void reject(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"unauthorized\"}");
	}

}
