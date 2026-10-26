package app.adera.notification.reset;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public (no session), through the gateway with strict per-IP limits. See {@link PasswordResetService}. */
@RestController
@RequestMapping("/public/v1/password-reset")
public class PasswordResetController {

	public record StartRequest(@NotBlank String identifier) {
	}

	public record FinishRequest(@NotBlank String identifier, @NotBlank String code, String password) {
	}

	private final PasswordResetService service;

	public PasswordResetController(PasswordResetService service) {
		this.service = service;
	}

	@PostMapping("/start")
	public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartRequest request) {
		PasswordResetService.Started s = service.start(request.identifier());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "sent", "expiresInSeconds", s.expiresInSeconds()));
	}

	@PostMapping("/finish")
	public Map<String, Object> finish(@Valid @RequestBody FinishRequest request) {
		service.finish(request.identifier(), request.code(), request.password());
		return Map.of("status", "password_changed");
	}

}
