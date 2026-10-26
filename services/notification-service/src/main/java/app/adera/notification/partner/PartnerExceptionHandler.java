package app.adera.notification.partner;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** One error shape for the partner, admin and user APIs: {@code {"error": "<code>", ...details}}. */
@RestControllerAdvice
public class PartnerExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(PartnerExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> api(ApiException e) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", e.code());
		body.putAll(e.details());
		return ResponseEntity.status(e.status()).body(body);
	}

	@ExceptionHandler(UpstreamUnavailableException.class)
	ResponseEntity<Map<String, Object>> upstream(UpstreamUnavailableException e) {
		log.error("dependency unavailable: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "service_unavailable"));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Map<String, Object>> unreadable(Exception e) {
		return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
	}

	@ExceptionHandler(ServletRequestBindingException.class)
	ResponseEntity<Map<String, Object>> binding(ServletRequestBindingException e) {
		return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
	}

}
