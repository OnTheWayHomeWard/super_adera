package app.adera.notification.gate;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no session: sign-in screens call it before the user is signed in), reached through the gateway at
 * {@code /public/v1/login-codes/check} with a strict per-IP limit. See {@link LoginCodeGate} for what it answers and why it
 * is safe to expose.
 */
@RestController
@RequestMapping("/public/v1/login-codes")
public class LoginCodeCheckController {

	public record CheckRequest(@NotBlank String identifier) {
	}

	private final LoginCodeGate gate;

	public LoginCodeCheckController(LoginCodeGate gate) {
		this.gate = gate;
	}

	@PostMapping("/check")
	public ResponseEntity<Map<String, Object>> check(@Valid @RequestBody CheckRequest request) {
		LoginCodeGate.Verdict v = gate.check(request.identifier());
		Map<String, Object> body = new LinkedHashMap<>();
		if (v.allowed()) {
			body.put("allowed", true);
		}
		else {
			body.put("error", "too_many_codes");
			body.put("allowed", false);
			body.put("retryAfterSeconds", v.retryAfterSeconds());
		}
		body.put("limitPerHour", v.limitPerHour());
		body.put("limitPerDay", v.limitPerDay());
		body.put("remainingThisHour", v.remainingThisHour());
		body.put("remainingToday", v.remainingToday());
		ResponseEntity.BodyBuilder response = ResponseEntity.status(v.allowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS);
		if (!v.allowed()) {
			response.header("Retry-After", String.valueOf(v.retryAfterSeconds()));
		}
		return response.body(body);
	}

}
