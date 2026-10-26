package app.adera.registry.common;

import java.util.LinkedHashMap;
import java.util.Map;

import app.adera.registry.auth.KratosClient;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** One error shape for the whole API: {@code {"error": "<code>", "fields": {...}}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(Errors.ValidationFailed.class)
	ResponseEntity<Map<String, Object>> validation(Errors.ValidationFailed e) {
		return body(HttpStatus.BAD_REQUEST, "validation_failed", e.fields());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> beanValidation(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
		return body(HttpStatus.BAD_REQUEST, "validation_failed", fields);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Map<String, Object>> unreadable(Exception e) {
		return body(HttpStatus.BAD_REQUEST, "invalid_request", null);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e) {
		return body(HttpStatus.UNAUTHORIZED, "unauthorized", null);
	}

	@ExceptionHandler(Errors.NotFound.class)
	ResponseEntity<Map<String, Object>> notFound(Errors.NotFound e) {
		return body(HttpStatus.NOT_FOUND, "not_found", null);
	}

	@ExceptionHandler(Errors.Forbidden.class)
	ResponseEntity<Map<String, Object>> forbidden(Errors.Forbidden e) {
		return body(HttpStatus.FORBIDDEN, e.code(), null);
	}

	@ExceptionHandler(Errors.Conflict.class)
	ResponseEntity<Map<String, Object>> conflict(Errors.Conflict e) {
		return body(HttpStatus.CONFLICT, e.code(), e.details().isEmpty() ? null : e.details());
	}

	@ExceptionHandler({ Errors.TooLarge.class, MaxUploadSizeExceededException.class })
	ResponseEntity<Map<String, Object>> tooLarge(Exception e) {
		return body(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", null);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
		log.warn("data integrity violation: {}", e.getMostSpecificCause().getMessage());
		return body(HttpStatus.CONFLICT, "conflict", null);
	}

	@ExceptionHandler(KratosClient.KratosUnavailableException.class)
	ResponseEntity<Map<String, Object>> kratosDown(KratosClient.KratosUnavailableException e) {
		log.error("kratos unavailable: {}", e.getMessage());
		return body(HttpStatus.SERVICE_UNAVAILABLE, "identity_service_unavailable", null);
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

	private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, Map<String, String> fields) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("error", error);
		if (fields != null) {
			out.put("fields", fields);
		}
		return ResponseEntity.status(status).body(out);
	}

}
