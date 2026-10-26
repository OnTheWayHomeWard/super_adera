package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import app.adera.notification.partner.template.TemplateRules;
import org.junit.jupiter.api.Test;

/** The "no links in values" rule: real link forms are refused, ordinary text (names!) is not. */
class LinkRuleTest {

	private static boolean blocked(String value) {
		try {
			TemplateRules.checkValues(List.of(Map.of("name", "v", "maxLength", 100)), Map.of("v", value));
			return false;
		}
		catch (app.adera.notification.partner.ApiException e) {
			return e.status() == 422;
		}
	}

	@Test
	void realLinkFormsAreBlocked() {
		for (String link : new String[] { "http://evil.example", "https://evil.example/x", "HTTPS://EVIL.EXAMPLE", "ftp://host/file", "visit http: now", "www.evil.example",
				"see WWW.evil.co", "evil.com/login", "evil.co/login", "Hana.co/pay", "evil.me?x=1", "evil.io#top", "evil.com:8080", "evil.ly:443/x", "evil.com", "evil.net", "evil.org",
				"adera.et", "evil.app", "pay.info", "get.xyz", "login.evil.co", "a.b.c.io", "click here: bit.ly/abc", "go to Evil.COM now", "x://y" }) {
			assertThat(blocked(link)).as("should be blocked: " + link).isTrue();
		}
	}

	@Test
	void ordinaryTextAndNamesAreAllowed() {
		for (String text : new String[] { "Hana.co", "Hana.Co", "Abebe Kebede", "Sam.me", "Al.io", "Ana.ly", "Dr. Hana", "7 min", "Order #1234", "12.50 ETB", "3.5 km", "a.b", "Mr.Co.", "Hana.co.",
				"Hello, world.", "e.g. tomorrow", "i.e. now", "Ato Kebede (Hana.co)", "Hana.company", "Tom.comfort", "it costs 1.000.000", "Hana.co, Addis", "co.op" }) {
			assertThat(blocked(text)).as("should be allowed: " + text).isFalse();
		}
	}

	@Test
	void aParamThatAllowsLinksSkipsTheCheck() {
		Map<String, Object> allowed = Map.of("name", "v", "maxLength", 100, "allowLinks", true);
		assertThat(TemplateRules.checkValues(List.of(allowed), Map.of("v", "https://pay.example/x"))).containsEntry("v", "https://pay.example/x");
	}

}
