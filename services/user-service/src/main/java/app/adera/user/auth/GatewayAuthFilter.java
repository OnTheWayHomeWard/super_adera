package app.adera.user.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import app.adera.user.config.UserProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Decides who may call what:
 * <ul>
 * <li>{@code /admin/api/**} (dashboard staff): through the gateway, with the verified {@code X-Staff-Id} and {@code X-Staff-Role};</li>
 * <li>{@code /api/**} and {@code /internal/auth/verify}: only through the gateway ({@code X-Gateway-Secret}); {@code /api}
 * additionally needs the verified {@code X-User-Id} the gateway sets (and overwrites if a client tries to send its own);</li>
 * <li>{@code /internal/aliases/**}, {@code /internal/users/**}, {@code /internal/devices/**}: only other services, with
 * {@code X-Internal-Secret}. The gateway never routes these.</li>
 * </ul>
 * An empty secret disables its check (local dev only).
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

	public static final String USER_ID_HEADER = "X-User-Id";
	public static final String SECRET_HEADER = "X-Gateway-Secret";
	public static final String INTERNAL_HEADER = "X-Internal-Secret";

	private final byte[] gatewaySecret;
	private final byte[] internalSecret;

	public GatewayAuthFilter(UserProperties props) {
		this.gatewaySecret = props.gatewaySecret().getBytes(StandardCharsets.UTF_8);
		this.internalSecret = props.internalSecret().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !(path.startsWith("/api/") || path.startsWith("/admin/api/") || path.equals("/internal/auth/verify") || isInternalService(path));
	}

	private static boolean isInternalService(String path) {
		return path.startsWith("/internal/aliases") || path.startsWith("/internal/users/") || path.startsWith("/internal/devices/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		if (isInternalService(path)) {
			if (!matches(request.getHeader(INTERNAL_HEADER), internalSecret)) {
				reject(response);
				return;
			}
			chain.doFilter(request, response);
			return;
		}
		if (!matches(request.getHeader(SECRET_HEADER), gatewaySecret)) {
			reject(response);
			return;
		}
		if (path.startsWith("/admin/api/")) {
			if (!uuidOk(request.getHeader("X-Staff-Id")) || !roleOk(request.getHeader("X-Staff-Role"))) {
				reject(response);
				return;
			}
		}
		if (path.startsWith("/api/")) {
			try {
				UUID.fromString(String.valueOf(request.getHeader(USER_ID_HEADER)));
			}
			catch (IllegalArgumentException e) {
				reject(response);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private static boolean uuidOk(String value) {
		try {
			UUID.fromString(String.valueOf(value));
			return true;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static boolean roleOk(String value) {
		try {
			app.adera.user.admin.StaffRole.valueOf(String.valueOf(value));
			return true;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static boolean matches(String sent, byte[] expected) {
		if (expected.length == 0) {
			return true;
		}
		return sent != null && MessageDigest.isEqual(expected, sent.getBytes(StandardCharsets.UTF_8));
	}

	private static void reject(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"unauthorized\"}");
	}

}
