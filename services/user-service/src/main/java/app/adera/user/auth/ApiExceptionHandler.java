package app.adera.user.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import app.adera.user.avatar.AvatarService;
import app.adera.user.profile.NotFoundException;
import app.adera.user.profile.ValidationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** One error shape for the whole API: {@code {"error": "<code>", "fields": {...}}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ValidationFailedException.class)
	ResponseEntity<Map<String, Object>> validation(ValidationFailedException e) {
		return body(HttpStatus.BAD_REQUEST, "validation_failed", e.fields());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> beanValidation(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
		return body(HttpStatus.BAD_REQUEST, "validation_failed", fields);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
		return body(HttpStatus.BAD_REQUEST, "invalid_json", null);
	}

	@ExceptionHandler({ AvatarService.TooLargeException.class, MaxUploadSizeExceededException.class })
	ResponseEntity<Map<String, Object>> tooLarge(Exception e) {
		return body(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", null);
	}

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
		return body(HttpStatus.NOT_FOUND, "not_found", null);
	}

	@ExceptionHandler(app.adera.user.admin.ApiException.class)
	ResponseEntity<Map<String, Object>> api(app.adera.user.admin.ApiException e) {
		return ResponseEntity.status(e.status()).body(Map.of("error", e.code()));
	}

	@ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
	ResponseEntity<Map<String, Object>> missingHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
		return body(HttpStatus.UNAUTHORIZED, "unauthorized", null);
	}

	/** e.g. another account already owns this email or phone number. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException e) {
		log.warn("data integrity violation: {}", e.getMostSpecificCause().getMessage());
		return body(HttpStatus.CONFLICT, "conflict", null);
	}

	/** Kratos down is "try again later", not "your session is invalid". */
	@ExceptionHandler(KratosClient.KratosUnavailableException.class)
	ResponseEntity<Map<String, Object>> kratosDown(KratosClient.KratosUnavailableException e) {
		log.error("kratos unavailable: {}", e.getMessage());
		return body(HttpStatus.SERVICE_UNAVAILABLE, "identity_service_unavailable", null);
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
