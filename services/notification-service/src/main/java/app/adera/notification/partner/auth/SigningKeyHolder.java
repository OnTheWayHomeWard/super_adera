package app.adera.notification.partner.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.partner.PartnerProperties;
import app.adera.notification.email.EmailProvider;
import app.adera.notification.sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Our signing key: signs every API response and every callback. The private key comes from Infisical
 * ({@code PARTNER_SIGNING_PRIVATE_KEY_B64}, a base64 PKCS#8 PEM); partners fetch the public half from
 * {@code /.well-known/jwks.json}. Without one configured an EPHEMERAL key is generated, which is only allowed while no
 * real provider is on (every restart would otherwise invalidate what partners have cached).
 */
@Component
public class SigningKeyHolder {

	private static final Logger log = LoggerFactory.getLogger(SigningKeyHolder.class);

	private final String keyId;
	private final PrivateKey privateKey;
	private final RSAPublicKey publicKey;

	@Autowired
	public SigningKeyHolder(PartnerProperties props, SmsProvider sms, EmailProvider email) {
		try {
			if (props.signingPrivateKeyB64().isBlank()) {
				if (sms.isReal() || email.isReal()) {
					throw new IllegalStateException("PARTNER_SIGNING_PRIVATE_KEY_B64 is not set but a real provider is enabled (check Infisical /notification)");
				}
				KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
				generator.initialize(2048);
				KeyPair pair = generator.generateKeyPair();
				this.privateKey = pair.getPrivate();
				this.publicKey = (RSAPublicKey) pair.getPublic();
				this.keyId = "ephemeral-" + UUID.randomUUID().toString().substring(0, 8);
				log.warn("No signing key configured: using an EPHEMERAL key ({}). Partners cannot rely on it across restarts.", keyId);
			}
			else {
				String pem = new String(Base64.getDecoder().decode(props.signingPrivateKeyB64().trim()), StandardCharsets.UTF_8);
				byte[] der = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
				KeyFactory factory = KeyFactory.getInstance("RSA");
				this.privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
				RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
				this.publicKey = (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
				this.keyId = props.signingKeyId().isBlank() ? "adera-sig-1" : props.signingKeyId();
			}
		}
		catch (IllegalStateException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IllegalStateException("PARTNER_SIGNING_PRIVATE_KEY_B64 is not a valid base64 PKCS#8 RSA private key PEM", e);
		}
	}

	/** For tests that want to supply their own key pair. */
	public SigningKeyHolder(String keyId, PrivateKey privateKey, RSAPublicKey publicKey) {
		this.keyId = keyId;
		this.privateKey = privateKey;
		this.publicKey = publicKey;
	}

	public String keyId() {
		return keyId;
	}

	public PublicKey publicKey() {
		return publicKey;
	}

	public String sign(String canonical) {
		return Signatures.sign(privateKey, canonical);
	}

	/** The public key in the standard JWKS format. */
	public Map<String, Object> jwks() {
		Map<String, Object> jwk = new LinkedHashMap<>();
		jwk.put("kty", "RSA");
		jwk.put("use", "sig");
		jwk.put("alg", "RS256");
		jwk.put("kid", keyId);
		jwk.put("n", b64url(publicKey.getModulus()));
		jwk.put("e", b64url(publicKey.getPublicExponent()));
		return Map.of("keys", List.of(jwk));
	}

	private static String b64url(BigInteger n) {
		byte[] bytes = n.toByteArray();
		if (bytes.length > 1 && bytes[0] == 0) {
			bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
