package app.adera.registry.key;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.app.MiniAppRepository;
import app.adera.registry.audit.Actor;
import app.adera.registry.audit.AuditService;
import app.adera.registry.common.Environment;
import app.adera.registry.common.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerKeyService {

	private static final int MAX_ACTIVE_PER_ENV = 5;

	public record Created(PartnerKey key, String privateKeyPem) {
	}

	private final PartnerKeyRepository keys;
	private final MiniAppRepository apps;
	private final AuditService audit;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public PartnerKeyService(PartnerKeyRepository keys, MiniAppRepository apps, AuditService audit, Clock clock) {
		this.keys = keys;
		this.apps = apps;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<PartnerKey> list(UUID appId) {
		requireApp(appId);
		return keys.findByAppIdOrderByCreatedAtDesc(appId);
	}

	/** Registers a public key the partner generated themselves (the recommended way). */
	@Transactional
	public PartnerKey register(UUID appId, Environment environment, String publicKeyPem, String label, Instant expiresAt, Actor actor) {
		requireApp(appId);
		PemKeys.Parsed parsed = PemKeys.parsePublic(publicKeyPem);
		return store(appId, environment, parsed, label, expiresAt, actor);
	}

	/**
	 * Convenience for DEV: generates a key pair, stores only the public half, and returns the private key ONCE.
	 * We cannot show it again; partners who need a PROD key should generate their own and send the public key.
	 */
	@Transactional
	public Created generate(UUID appId, Environment environment, String label, Instant expiresAt, Actor actor) {
		requireApp(appId);
		KeyPair pair = PemKeys.generate();
		byte[] der = pair.getPublic().getEncoded();
		PemKeys.Parsed parsed = PemKeys.parsePublic(PemKeys.pemOf(der));
		PartnerKey stored = store(appId, environment, parsed, label, expiresAt, actor);
		return new Created(stored, PemKeys.privatePemOf(pair.getPrivate().getEncoded()));
	}

	@Transactional
	public PartnerKey revoke(UUID appId, String keyId, Actor actor) {
		PartnerKey key = keys.findById(keyId).filter(k -> k.getAppId().equals(appId)).orElseThrow(() -> new Errors.NotFound("key"));
		if (!"REVOKED".equals(key.getStatus())) {
			key.revoke(clock.instant());
			audit.record(actor, "key.revoke", "partner_key", keyId, Map.of("appId", appId.toString()));
		}
		return key;
	}

	private PartnerKey store(UUID appId, Environment environment, PemKeys.Parsed parsed, String label, Instant expiresAt, Actor actor) {
		Instant now = clock.instant();
		if (expiresAt != null && !expiresAt.isAfter(now)) {
			throw new Errors.ValidationFailed("expiresAt", "must be in the future");
		}
		if (label != null && label.length() > 100) {
			throw new Errors.ValidationFailed("label", "max 100 characters");
		}
		if (keys.countByAppIdAndEnvironmentAndStatus(appId, environment.name(), "ACTIVE") >= MAX_ACTIVE_PER_ENV) {
			throw new Errors.Conflict("too_many_active_keys", Map.of("max", String.valueOf(MAX_ACTIVE_PER_ENV)));
		}
		byte[] id = new byte[12];
		random.nextBytes(id);
		String keyId = "ak_" + environment.name().toLowerCase(Locale.ROOT) + "_" + HexFormat.of().formatHex(id);
		PartnerKey key = keys.save(new PartnerKey(keyId, appId, environment.name(), parsed.canonicalPem(), parsed.fingerprint(),
				label == null || label.isBlank() ? null : label.strip(), expiresAt, actor == null ? null : actor.id(), now));
		audit.record(actor, "key.register", "partner_key", keyId,
				Map.of("appId", appId.toString(), "environment", environment.name(), "fingerprint", parsed.fingerprint()));
		return key;
	}

	private void requireApp(UUID appId) {
		if (!apps.existsById(appId)) {
			throw new Errors.NotFound("mini app");
		}
	}

}
