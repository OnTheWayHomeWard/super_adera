package app.adera.notification.kratos;

import java.util.Map;

import app.adera.notification.dispatch.DeliveryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	/** A real delivery failure is a 502 so Kratos keeps the message queued and retries it. */
	@ExceptionHandler(DeliveryException.class)
	ResponseEntity<Map<String, String>> delivery(DeliveryException e) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "delivery_failed", "code", e.code()));
	}

}
