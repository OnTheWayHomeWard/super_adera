package app.adera.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GsmTextTest {

	@Test
	void replacesFancyPunctuation() {
		assertThat(GsmText.toGsmSafe("Bole → Airport – “ready”…")).isEqualTo("Bole -> Airport - \"ready\"...");
	}

	@Test
	void leavesPlainAndAmharicTextAlone() {
		assertThat(GsmText.toGsmSafe("Your code is 123456")).isEqualTo("Your code is 123456");
		assertThat(GsmText.toGsmSafe("ሰላም")).isEqualTo("ሰላም");
		assertThat(GsmText.toGsmSafe(null)).isNull();
	}

}
