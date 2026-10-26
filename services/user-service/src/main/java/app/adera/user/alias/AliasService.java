package app.adera.user.alias;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.adera.user.config.UserProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pseudonymous ids for partners: {@code u_p_} + the first 32 hex chars of HMAC-SHA256(secret, userId:appId).
 * The same user has a different alias in each app, and an alias cannot be turned back into a user id without our table,
 * so a partner who leaks its data exposes nothing that links to another partner or to us.
 * Resolving needs the table, so an alias exists only once it has been minted (when the user first opens that app).
 */
@Service
public class AliasService {

	private final JdbcClient jdbc;
	private final byte[] key;
	private final Clock clock;

	public AliasService(JdbcClient jdbc, UserProperties props, Clock clock) {
		this.jdbc = jdbc;
		this.key = props.aliasHmacSecret().getBytes(StandardCharsets.UTF_8);
		this.clock = clock;
		if (key.length == 0) {
			throw new IllegalStateException("ALIAS_HMAC_SECRET is not set: partner ids cannot be derived safely");
		}
	}

	/** Same inputs always give the same alias, so minting twice is harmless. The user must already have a profile. */
	@Transactional
	public String mint(UUID appId, UUID userId) {
		String alias = derive(appId, userId);
		jdbc.sql("""
				INSERT INTO app_user_alias (app_id, alias, user_id, created_at) VALUES (:app, :alias, :user, :now)
				ON CONFLICT (app_id, alias) DO NOTHING""")
			.param("app", appId).param("alias", alias).param("user", userId).param("now", java.sql.Timestamp.from(clock.instant())).update();
		return alias;
	}

	@Transactional(readOnly = true)
	public Optional<UUID> resolve(UUID appId, String alias) {
		return jdbc.sql("SELECT user_id FROM app_user_alias WHERE app_id = :app AND alias = :alias")
			.param("app", appId).param("alias", alias).query(UUID.class).optional();
	}

	String derive(UUID appId, UUID userId) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			byte[] digest = mac.doFinal((userId + ":" + appId).getBytes(StandardCharsets.UTF_8));
			return "u_p_" + HexFormat.of().formatHex(digest).substring(0, 32);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
