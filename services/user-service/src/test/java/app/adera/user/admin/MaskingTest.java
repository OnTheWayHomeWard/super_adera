package app.adera.user.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

	@Test
	void emailKeepsTheFirstCharacterAndTheTopLevelDomain() {
		assertThat(Masking.email("alice@gmail.com")).isEqualTo("a***@g***.com");
		assertThat(Masking.email("bob@mail.co.et")).isEqualTo("b***@m***.et");
		assertThat(Masking.email(null)).isNull();
		assertThat(Masking.email("nonsense")).isEqualTo("***");
	}

	@Test
	void phoneKeepsTheCountryPartAndTheLastFourDigits() {
		assertThat(Masking.phone("+251911234567")).isEqualTo("+251*****4567");
		assertThat(Masking.phone("+12025550123")).isEqualTo("+1******0123");
		assertThat(Masking.phone("+2519")).isEqualTo("***19");
		assertThat(Masking.phone(null)).isNull();
	}

}
