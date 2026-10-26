package app.adera.notification.partner.auth;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import app.adera.notification.partner.PartnerProperties;
import app.adera.notification.partner.UpstreamUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /v1/**}: a request is accepted only if
 * <ol>
 * <li>it carries a timestamp within the allowed clock skew,</li>
 * <li>its signature verifies against the partner's registered PUBLIC key (so only the holder of the private key can
 * have produced it), over the method, path, timestamp and a hash of the whole body,</li>
 * <li>the key is active and belongs to the environment this deployment runs (ADERA_ENV; otherwise 403 wrong_environment) and to an app that may run in it,</li>
 * <li>and it comes from one of that app's registered server addresses.</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class PartnerAuthFilter extends OncePerRequestFilter {

	static final int MAX_BODY = 64 * 1024;

	private final RegistryClient registry;
	private final PartnerProperties props;
	private final byte[] gatewaySecret;

	public PartnerAuthFilter(RegistryClient registry, PartnerProperties props) {
		this.registry = registry;
		this.props = props;
		this.gatewaySecret = props.gatewaySecret().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/v1/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
		if (body.length > MAX_BODY) {
			reject(response, 413, "request_too_large");
			return;
		}
		String keyId = request.getHeader("X-Adera-Key-Id");
		String timestamp = request.getHeader("X-Adera-Timestamp");
		String signature = request.getHeader("X-Adera-Signature");
		if (blank(keyId) || blank(timestamp) || blank(signature)) {
			reject(response, 401, "missing_signature");
			return;
		}
		long ts;
		try {
			ts = Long.parseLong(timestamp.trim());
		}
		catch (NumberFormatException e) {
			reject(response, 401, "invalid_timestamp");
			return;
		}
		if (Math.abs(Instant.now().getEpochSecond() - ts) > props.maxSkewMinutes() * 60L) {
			reject(response, 401, "stale_timestamp");
			return;
		}
		try {
			Optional<RegistryClient.ParsedKey> key = registry.key(keyId.trim());
			if (key.isEmpty()) {
				reject(response, 401, "unknown_key");
				return;
			}
			if (!key.get().info().usable()) {
				reject(response, 401, "key_revoked_or_expired");
				return;
			}
			String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
			String canonical = Signatures.canonicalRequest(timestamp.trim(), request.getMethod(), path, body);
			if (!Signatures.verify(key.get().publicKey(), canonical, signature)) {
				reject(response, 401, "invalid_signature");
				return;
			}
			RegistryClient.KeyInfo info = key.get().info();
			// one environment per deployment: a key of the other environment is valid somewhere else, not here
			if (!registry.deployment().equals(info.environment())) {
				reject(response, 403, "wrong_environment");
				return;
			}
			if (info.deploymentEnvironment() != null && !registry.deployment().equals(info.deploymentEnvironment())) {
				logger.error("partner auth: registry-service runs as " + info.deploymentEnvironment() + " but this service as " + registry.deployment()
						+ " (ADERA_ENV differs between the two)");
				reject(response, 503, "service_unavailable");
				return;
			}
			Optional<RegistryClient.AppContext> app = registry.context(info.appId());
			if (app.isEmpty()) {
				reject(response, 401, "unknown_app");
				return;
			}
			if (!app.get().usable()) {
				reject(response, 403, app.get().denyReason() == null ? "app_not_active" : app.get().denyReason());
				return;
			}
			if (!Cidrs.matchesAny(app.get().allowedCidrs(), clientIp(request))) {
				reject(response, 403, "ip_not_allowed");
				return;
			}
			request.setAttribute(PartnerContext.ATTR, new PartnerContext(info.appId(), registry.deployment(), info.keyId(), app.get().slug(),
					Set.copyOf(app.get().grantedCapabilities()), app.get().notificationLimits(), signature.trim()));
		}
		catch (UpstreamUnavailableException e) {
			logger.error("partner auth: " + e.getMessage());
			reject(response, 503, "service_unavailable");
			return;
		}
		chain.doFilter(new CachedBodyRequest(request, body), response);
	}

	/** The gateway puts the real caller address in X-Client-IP; it is believed only when the gateway secret proves it was the gateway. */
	private String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Client-IP");
		boolean fromGateway = gatewaySecret.length == 0 || (request.getHeader("X-Gateway-Secret") != null
				&& MessageDigest.isEqual(gatewaySecret, request.getHeader("X-Gateway-Secret").getBytes(StandardCharsets.UTF_8)));
		return fromGateway && forwarded != null && !forwarded.isBlank() ? forwarded.trim() : request.getRemoteAddr();
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static void reject(HttpServletResponse response, int status, String code) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"" + code + "\"}");
	}

	/** Lets controllers read the body again after the filter consumed it to verify the signature. */
	static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream in = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public boolean isFinished() {
					return in.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
				}

				@Override
				public int read() {
					return in.read();
				}

				@Override
				public int read(byte[] b, int off, int len) {
					return in.read(b, off, len);
				}
			};
		}

		@Override
		public java.io.BufferedReader getReader() {
			return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}

	}

}
