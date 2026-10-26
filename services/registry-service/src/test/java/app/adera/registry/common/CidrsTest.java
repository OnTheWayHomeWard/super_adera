package app.adera.registry.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CidrsTest {

	@Test
	void normalisesBareAddressesAndRanges() {
		assertThat(Cidrs.normalise("203.0.113.10")).isEqualTo("203.0.113.10/32");
		assertThat(Cidrs.normalise(" 198.51.100.0/24 ")).isEqualTo("198.51.100.0/24");
		assertThat(Cidrs.normalise("2001:db8::1")).endsWith("/128");
		assertThat(Cidrs.normalise("2001:db8::/32")).endsWith("/32");
	}

	@Test
	void rejectsNonsense() {
		for (String bad : new String[] { "", "example.com", "999.1.1.1", "10.0.0.1/33", "10.0.0.1/-1", "10.0.0.1/x", "1.2.3", "10.0.0.1/8/8", "::zz" }) {
			assertThatThrownBy(() -> Cidrs.normalise(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void neverResolvesHostnames() {
		// would trigger a DNS lookup if we let InetAddress parse it; must be rejected up front
		assertThatThrownBy(() -> Cidrs.normalise("localhost")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void detectsTheMatchEverythingRange() {
		assertThat(Cidrs.matchesEverything(Cidrs.normalise("0.0.0.0/0"))).isTrue();
		assertThat(Cidrs.matchesEverything(Cidrs.normalise("10.0.0.0/8"))).isFalse();
	}

}
