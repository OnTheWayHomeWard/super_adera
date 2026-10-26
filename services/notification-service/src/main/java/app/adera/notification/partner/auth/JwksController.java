package app.adera.notification.partner.auth;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Our public key, so partners can check that a response or callback really came from us. Public, cacheable. */
@RestController
public class JwksController {

	private final SigningKeyHolder keys;

	public JwksController(SigningKeyHolder keys) {
		this.keys = keys;
	}

	@GetMapping("/.well-known/jwks.json")
	public ResponseEntity<Map<String, Object>> jwks() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic()).body(keys.jwks());
	}

}
