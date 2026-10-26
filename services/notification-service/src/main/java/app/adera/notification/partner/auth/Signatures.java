package app.adera.notification.partner.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * The signing scheme, "ADERA-SIG-1": RSASSA-PKCS1-v1_5 with SHA-256 (RS256) over a canonical string.
 *
 * <pre>
 * request   : ADERA-SIG-1 \n timestamp \n METHOD \n path[?query] \n base64(sha256(body))
 * response  : ADERA-SIG-1-RESPONSE \n timestamp \n status \n base64(sha256(body)) \n base64(sha256(request signature))
 * callback  : same as a request (we are the caller): ADERA-SIG-1 \n timestamp \n POST \n path[?query] \n base64(sha256(body))
 * </pre>
 *
 * The body hash covers the template id, the user, the values and the external id, so changing anything invalidates it.
 * The response signature is bound to the request signature it answers, so a response cannot be replayed for another call.
 */
public final class Signatures {

	private Signatures() {
	}

	public static String canonicalRequest(String timestamp, String method, String pathAndQuery, byte[] body) {
		return "ADERA-SIG-1\n" + timestamp + "\n" + method.toUpperCase() + "\n" + pathAndQuery + "\n" + sha256B64(body);
	}

	public static String canonicalResponse(String timestamp, int status, byte[] body, String requestSignature) {
		return "ADERA-SIG-1-RESPONSE\n" + timestamp + "\n" + status + "\n" + sha256B64(body) + "\n"
				+ sha256B64(requestSignature == null ? new byte[0] : requestSignature.getBytes(StandardCharsets.UTF_8));
	}

	public static String sha256B64(byte[] data) {
		try {
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public static String sign(PrivateKey key, String canonical) {
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(key);
			signature.update(canonical.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(signature.sign());
		}
		catch (Exception e) {
			throw new IllegalStateException("signing failed", e);
		}
	}

	/** Accepts standard or URL-safe base64 (with or without padding), because partners' libraries differ. */
	public static boolean verify(PublicKey key, String canonical, String signatureB64) {
		try {
			byte[] raw = decode(signatureB64);
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initVerify(key);
			signature.update(canonical.getBytes(StandardCharsets.UTF_8));
			return signature.verify(raw);
		}
		catch (Exception e) {
			return false;
		}
	}

	private static byte[] decode(String value) {
		String v = value.trim();
		return v.contains("-") || v.contains("_") ? Base64.getUrlDecoder().decode(v) : Base64.getDecoder().decode(v);
	}

}
