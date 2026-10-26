package app.adera.notification.partner;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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

	/** A body that fails bean validation (the Kratos courier endpoints): same shape as the other services. */
	@ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> beanValidation(org.springframework.web.bind.MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("error", "validation_failed", "fields", fields));
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
	}

	@ExceptionHandler(ServletRequestBindingException.class)
	ResponseEntity<Map<String, Object>> binding(ServletRequestBindingException e) {
		return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
	}

	// ---- framework errors that never reach a controller: same {"error": code} shape as everything else ----

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
		ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(MediaType.APPLICATION_JSON);
		if (e.getSupportedHttpMethods() != null && !e.getSupportedHttpMethods().isEmpty()) {
			b.allow(e.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
		}
		return b.body(Map.of("error", "method_not_allowed"));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<Map<String, Object>> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", "unsupported_media_type"));
	}

	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	ResponseEntity<Map<String, Object>> notAcceptable(HttpMediaTypeNotAcceptableException e) {
		return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", "not_acceptable"));
	}

	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	ResponseEntity<Map<String, Object>> unknownRoute(Exception e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", "not_found"));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<Map<String, Object>> missingParameter(MissingServletRequestParameterException e) {
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("error", "validation_failed", "fields", Map.of(e.getParameterName(), "is required")));
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	ResponseEntity<Map<String, Object>> missingPart(MissingServletRequestPartException e) {
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("error", "validation_failed", "fields", Map.of(e.getRequestPartName(), "is required")));
	}

}
