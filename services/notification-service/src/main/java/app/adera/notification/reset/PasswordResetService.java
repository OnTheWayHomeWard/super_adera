package app.adera.notification.reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.notification.dispatch.Masking;
import app.adera.notification.dispatch.NotificationDispatcher;
import app.adera.notification.gate.LoginCodeGate;
import app.adera.notification.partner.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "Forgot password" for an account that signs in with a PHONE.
 *
 * <p>Why this exists: Kratos' recovery flow only accepts an email address (its input is validated as an email), so a phone-only
 * account could not recover. Email recovery stays Kratos' own flow. For a phone we send the code ourselves (SMS), check it,
 * and then set the new password through Kratos' admin API and sign the account out everywhere. The user signs in afterwards
 * (no privileged session is handed out here, unlike Kratos' recovery flow).
 *
 * <p>Safety: the same answer for every well-formed phone (registered or not), so it cannot be used to find accounts; the
 * request count per phone goes through the same limit as every other code send ({@link LoginCodeGate}); a code lives
 * {@value #LIFETIME_MINUTES} minutes, allows {@value #MAX_ATTEMPTS} wrong tries, and works once; only staff accounts are refused.
 */
@Service
public class PasswordResetService {

	static final int LIFETIME_MINUTES = 10;
	static final int MAX_ATTEMPTS = 5;
	static final int MIN_PASSWORD = 6;
	private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final KratosAdminClient kratos;
	private final NotificationDispatcher dispatcher;
	private final LoginCodeGate gate;
	private final JdbcClient jdbc;
	private final Clock clock;

	public PasswordResetService(KratosAdminClient kratos, NotificationDispatcher dispatcher, LoginCodeGate gate, JdbcClient jdbc, Clock clock) {
		this.kratos = kratos;
		this.dispatcher = dispatcher;
		this.gate = gate;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public record Started(int expiresInSeconds) {
	}

	/** @throws ApiException 400 validation_failed (not a phone), 429 too_many_codes */
	public Started start(String identifier) {
		String phone = phone(identifier);
		LoginCodeGate.Verdict verdict = gate.check(phone);
		if (!verdict.allowed()) {
			throw new ApiException(429, "too_many_codes", java.util.Map.of("retryAfterSeconds", verdict.retryAfterSeconds()));
		}
		Optional<KratosAdminClient.Identity> identity = kratos.findBySignInIdentifier(phone)
			.filter(i -> "user".equals(i.schemaId()) && "active".equals(i.state()));
		if (identity.isPresent()) {
			Instant now = clock.instant();
			String code = String.format("%06d", RANDOM.nextInt(1_000_000));
			String salt = HexFormat.of().formatHex(randomBytes(8));
			String hash = Masking.hash(phone);
			jdbc.sql("DELETE FROM password_reset WHERE recipient_hash = :h OR created_at < :old")
				.param("h", hash).param("old", Timestamp.from(now.minus(Duration.ofDays(1)))).update();
			jdbc.sql("INSERT INTO password_reset (id, identity_id, recipient_hash, code_salt, code_hash, expires_at, created_at) VALUES (:id, :i, :h, :s, :c, :e, :n)")
				.param("id", UUID.randomUUID()).param("i", identity.get().id()).param("h", hash).param("s", salt).param("c", codeHash(salt, code))
				.param("e", Timestamp.from(now.plus(Duration.ofMinutes(LIFETIME_MINUTES)))).param("n", Timestamp.from(now)).update();
			dispatcher.sendSms(phone, "Adera: your password reset code is " + code + ". Do not share it with anyone. It expires in "
					+ LIFETIME_MINUTES + " minutes. If you did not ask for it, ignore this message.", "password_reset_code");
		}
		return new Started(LIFETIME_MINUTES * 60);
	}

	/** @throws ApiException 400 validation_failed (phone / password rules), 400 invalid_code (wrong, expired, used, too many tries, unknown phone) */
	public void finish(String identifier, String code, String newPassword) {
		String phone = phone(identifier);
		if (newPassword == null || newPassword.length() < MIN_PASSWORD) {
			throw ApiException.validation("password", "must be at least " + MIN_PASSWORD + " characters");
		}
		Instant now = clock.instant();
		var row = jdbc.sql("SELECT id, identity_id, code_salt, code_hash, attempts FROM password_reset WHERE recipient_hash = :h AND used_at IS NULL AND expires_at > :now ORDER BY created_at DESC LIMIT 1")
			.param("h", Masking.hash(phone)).param("now", Timestamp.from(now))
			.query((rs, n) -> new Object[] { rs.getObject("id", UUID.class), rs.getObject("identity_id", UUID.class), rs.getString("code_salt"), rs.getString("code_hash"), rs.getInt("attempts") })
			.optional();
		if (row.isEmpty() || (int) row.get()[4] >= MAX_ATTEMPTS) {
			throw new ApiException(400, "invalid_code");
		}
		UUID id = (UUID) row.get()[0];
		String given = code == null ? "" : code.trim();
		boolean match = MessageDigest.isEqual(((String) row.get()[3]).getBytes(StandardCharsets.UTF_8), codeHash((String) row.get()[2], given).getBytes(StandardCharsets.UTF_8));
		if (!match) {
			jdbc.sql("UPDATE password_reset SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
			throw new ApiException(400, "invalid_code");
		}
		// burn the code first: a second request with the same code can never win a race
		int used = jdbc.sql("UPDATE password_reset SET used_at = :now WHERE id = :id AND used_at IS NULL").param("now", Timestamp.from(now)).param("id", id).update();
		if (used != 1) {
			throw new ApiException(400, "invalid_code");
		}
		kratos.resetPassword((UUID) row.get()[1], newPassword);
	}

	private static String phone(String identifier) {
		String raw = identifier == null ? "" : identifier.trim();
		if (raw.contains("@")) {
			throw ApiException.validation("identifier", "a phone number like +251911234567; for an email address use Kratos' recovery flow");
		}
		String phone = raw.replaceAll("[\\s()-]", "");
		if (!PHONE.matcher(phone).matches()) {
			throw ApiException.validation("identifier", "must be a phone number like +251911234567");
		}
		return phone;
	}

	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		RANDOM.nextBytes(b);
		return b;
	}

	private static String codeHash(String salt, String code) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
