package app.adera.notification.partner.auth;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Signs every {@code /v1/**} response (errors included) with OUR key, so a partner can prove an answer is genuinely from
 * us and was not altered. The signature is bound to the request signature it answers.
 * Headers: X-Adera-Key-Id (our key id, see /.well-known/jwks.json), X-Adera-Timestamp, X-Adera-Signature.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ResponseSigningFilter extends OncePerRequestFilter {

	private final SigningKeyHolder keys;

	public ResponseSigningFilter(SigningKeyHolder keys) {
		this.keys = keys;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/v1/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
		try {
			chain.doFilter(request, wrapper);
		}
		finally {
			String timestamp = String.valueOf(Instant.now().getEpochSecond());
			String requestSignature = request.getHeader("X-Adera-Signature");
			String canonical = Signatures.canonicalResponse(timestamp, wrapper.getStatus(), wrapper.getContentAsByteArray(),
					requestSignature == null ? "" : requestSignature.trim());
			wrapper.setHeader("X-Adera-Key-Id", keys.keyId());
			wrapper.setHeader("X-Adera-Timestamp", timestamp);
			wrapper.setHeader("X-Adera-Signature", keys.sign(canonical));
			wrapper.copyBodyToResponse();
		}
	}

}
