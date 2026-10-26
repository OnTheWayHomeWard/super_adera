package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.adera.notification.partner.delivery.RetryPolicy;
import app.adera.notification.partner.delivery.UrlGuard;
import org.junit.jupiter.api.Test;

class UrlGuardTest {

	@Test
	void refusesAddressesInsideOurNetworkAndNonHttps() {
		for (String url : new String[] { "http://example.com/x", "https://127.0.0.1/x", "https://10.1.2.3/x", "https://192.168.0.5/x",
				"https://169.254.169.254/latest/meta-data", "https://[::1]/x", "https://100.64.0.1/x", "https://[fd00::1]/x",
				"https://user:pw@8.8.8.8/x", "https://8.8.8.8:22/x", "ftp://8.8.8.8/x" }) {
			assertThatThrownBy(() -> UrlGuard.check(url, false)).as(url).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void allowsPublicHttpsAddresses() {
		assertThat(UrlGuard.check("https://8.8.8.8/hook", false).getHost()).isEqualTo("8.8.8.8");
		assertThat(UrlGuard.check("https://8.8.8.8:8443/hook", false).getPort()).isEqualTo(8443);
	}

	@Test
	void devFlagAllowsLocalHttp() {
		assertThat(UrlGuard.check("http://127.0.0.1:9999/hook", true)).isNotNull();
	}

	@Test
	void retryDelaysDoubleAndPermanentProviderErrorsAreNeverRetried() {
		assertThat(RetryPolicy.deliveryDelay(1).toSeconds()).isBetween(27L, 33L);
		assertThat(RetryPolicy.deliveryDelay(3).toSeconds()).isBetween(108L, 132L); // 2 min +-10%
		assertThat(RetryPolicy.deliveryDelay(30).toMinutes()).isBetween(53L, 66L); // capped near 1 hour
		assertThat(RetryPolicy.isPermanent("twilio_http_400")).isTrue();
		assertThat(RetryPolicy.isPermanent("twilio_http_429")).isFalse();
		assertThat(RetryPolicy.isPermanent("twilio_http_503")).isFalse();
	}

}
