package app.adera.registry.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import app.adera.registry.common.Errors;
import org.junit.jupiter.api.Test;

class PemKeysTest {

	private static KeyPair rsa(int bits) throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(bits);
		return g.generateKeyPair();
	}

	@Test
	void acceptsAValidPublicKeyAndFingerprintsIt() throws Exception {
		KeyPair pair = rsa(2048);
		PemKeys.Parsed parsed = PemKeys.parsePublic(PemKeys.pemOf(pair.getPublic().getEncoded()));
		assertThat(parsed.fingerprint()).hasSize(64);
		assertThat(parsed.canonicalPem()).startsWith("-----BEGIN PUBLIC KEY-----");
		assertThat(PemKeys.parsePublic(parsed.canonicalPem()).fingerprint()).isEqualTo(parsed.fingerprint());
	}

	@Test
	void refusesAPrivateKeyWithAClearMessage() throws Exception {
		String privatePem = PemKeys.privatePemOf(rsa(2048).getPrivate().getEncoded());
		assertThatThrownBy(() -> PemKeys.parsePublic(privatePem)).isInstanceOf(Errors.ValidationFailed.class).hasMessageContaining("PRIVATE");
	}

	@Test
	void refusesWeakKeys() throws Exception {
		String weak = PemKeys.pemOf(rsa(1024).getPublic().getEncoded());
		assertThatThrownBy(() -> PemKeys.parsePublic(weak)).isInstanceOf(Errors.ValidationFailed.class).hasMessageContaining("2048");
	}

	@Test
	void refusesGarbage() {
		for (String bad : new String[] { null, "", "hello", "-----BEGIN PUBLIC KEY-----\nnot base64!!\n-----END PUBLIC KEY-----" }) {
			assertThatThrownBy(() -> PemKeys.parsePublic(bad)).isInstanceOf(Errors.ValidationFailed.class);
		}
	}

}
