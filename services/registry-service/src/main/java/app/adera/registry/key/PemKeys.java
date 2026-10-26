package app.adera.registry.key;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import app.adera.registry.common.Errors;

/** Parsing and checking the partner's RSA public key. Only the PUBLIC key is ever accepted or stored. */
public final class PemKeys {

	public static final int MIN_BITS = 2048;

	public record Parsed(RSAPublicKey key, String canonicalPem, String fingerprint) {
	}

	private PemKeys() {
	}

	public static Parsed parsePublic(String pem) {
		if (pem == null || pem.isBlank()) {
			throw new Errors.ValidationFailed("publicKeyPem", "required");
		}
		String text = pem.trim();
		if (text.contains("PRIVATE KEY")) {
			throw new Errors.ValidationFailed("publicKeyPem",
					"that is a PRIVATE key. Never send it: keep it on your server and send only the public key (BEGIN PUBLIC KEY)");
		}
		if (!text.startsWith("-----BEGIN PUBLIC KEY-----") || !text.contains("-----END PUBLIC KEY-----")) {
			throw new Errors.ValidationFailed("publicKeyPem", "must be a PEM public key starting with -----BEGIN PUBLIC KEY-----");
		}
		try {
			String body = text.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
			byte[] der = Base64.getDecoder().decode(body);
			PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
			RSAPublicKey rsa = (RSAPublicKey) key;
			if (rsa.getModulus().bitLength() < MIN_BITS) {
				throw new Errors.ValidationFailed("publicKeyPem", "RSA key must be at least " + MIN_BITS + " bits");
			}
			return new Parsed(rsa, pemOf(der), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der)));
		}
		catch (Errors.ValidationFailed e) {
			throw e;
		}
		catch (Exception e) {
			throw new Errors.ValidationFailed("publicKeyPem", "not a valid RSA public key");
		}
	}

	/** Convenience for DEV: a new key pair. The private half is returned once and never stored. */
	public static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(3072);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String pemOf(byte[] derPublicKey) {
		String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(derPublicKey);
		return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
	}

	public static String privatePemOf(byte[] derPkcs8) {
		String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(derPkcs8);
		return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
	}

}
