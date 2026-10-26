package app.adera.notification.partner.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import app.adera.notification.partner.PartnerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /admin/api/**} (dashboard staff) and {@code /api/**} (signed-in users) only make sense behind the gateway:
 * it must present the shared secret, and carry the verified staff id and role, or user id. An empty secret disables
 * the check (local dev only).
 */
@Component
public class GatewayTrustFilter extends OncePerRequestFilter {

	private final byte[] secret;

	public GatewayTrustFilter(PartnerProperties props) {
		this.secret = props.gatewaySecret().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !(path.startsWith("/admin/api/") || path.startsWith("/api/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean admin = request.getRequestURI().startsWith("/admin/api/");
		boolean ok = secretOk(request) && (admin ? staffOk(request) : uuid(request.getHeader("X-User-Id")));
		if (!ok) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"error\":\"unauthorized\"}");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean secretOk(HttpServletRequest request) {
		if (secret.length == 0) {
			return true;
		}
		String sent = request.getHeader("X-Gateway-Secret");
		return sent != null && MessageDigest.isEqual(secret, sent.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean staffOk(HttpServletRequest request) {
		try {
			StaffRole.valueOf(String.valueOf(request.getHeader("X-Staff-Role")));
			return uuid(request.getHeader("X-Staff-Id"));
		}
		catch (IllegalArgumentException e) {
			return false;
		}
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

}
