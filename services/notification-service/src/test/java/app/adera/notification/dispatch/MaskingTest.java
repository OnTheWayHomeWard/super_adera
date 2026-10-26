package app.adera.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

	@Test
	void masksPhones() {
		assertThat(Masking.phone("+251911234567")).isEqualTo("+2519******67");
		assertThat(Masking.phone("+1")).isEqualTo("***");
		assertThat(Masking.phone(null)).isEqualTo("***");
	}

	@Test
	void masksEmails() {
		assertThat(Masking.email("alice@example.com")).isEqualTo("a***@example.com");
		assertThat(Masking.email("nonsense")).isEqualTo("***");
	}

	@Test
	void hashIsStableCaseInsensitiveAndIrreversible() {
		assertThat(Masking.hash("Alice@Example.com ")).isEqualTo(Masking.hash("alice@example.com"));
		assertThat(Masking.hash("a")).hasSize(64).isNotEqualTo(Masking.hash("b"));
	}

}
