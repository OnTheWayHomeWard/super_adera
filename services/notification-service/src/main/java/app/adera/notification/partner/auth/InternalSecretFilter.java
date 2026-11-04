package app.adera.notification.partner.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import app.adera.notification.partner.PartnerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /internal/launch-tokens}: only other services, with {@code X-Internal-Secret}. The gateway never routes it. An empty
 * configured secret disables the check (local dev only), like everywhere else.
 */
@Component
public class InternalSecretFilter extends OncePerRequestFilter {

	private final byte[] secret;

	public InternalSecretFilter(PartnerProperties props) {
		this.secret = props.internalSecret().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/internal/launch-tokens");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		String sent = request.getHeader("X-Internal-Secret");
		boolean ok = secret.length == 0 || (sent != null && MessageDigest.isEqual(secret, sent.getBytes(StandardCharsets.UTF_8)));
		if (!ok) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"error\":\"unauthorized\"}");
			return;
		}
		chain.doFilter(request, response);
	}

}
