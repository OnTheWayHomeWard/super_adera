package app.adera.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.dispatch.DeliveryException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendEmailProviderTest {

	private static NotificationProperties props(String key, String from) {
		return new NotificationProperties("console", "resend", false, List.of(),
				new NotificationProperties.Limits(5, 10, 10, 20),
				new NotificationProperties.Twilio("", "", "", "", ""),
				new NotificationProperties.Resend("https://api.resend.com", key, from, "Adera"),
				new NotificationProperties.Mailpit("no-reply@adera.local", "Adera"));
	}

	@Test
	void postsJsonWithBearerKeyAndFormattedSender() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.resend.com/emails"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer re_test"))
			.andExpect(jsonPath("$.from").value("Adera <bookings@gobellme.com>"))
			.andExpect(jsonPath("$.to[0]").value("a@example.com"))
			.andExpect(jsonPath("$.subject").value("Use code 123456"))
			.andExpect(jsonPath("$.text").value("Your code is 123456"))
			.andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

		new ResendEmailProvider(props("re_test", "bookings@gobellme.com"), builder)
			.send(new EmailMessage("a@example.com", "Use code 123456", "Your code is 123456", null));
		server.verify();
	}

	@Test
	void unverifiedDomainOrBadKeyBecomesDeliveryException() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer.bindTo(builder).build()
			.expect(requestTo("https://api.resend.com/emails"))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).body("domain not verified for a@example.com"));

		var provider = new ResendEmailProvider(props("re_test", "x@y.com"), builder);
		assertThatThrownBy(() -> provider.send(new EmailMessage("a@example.com", "s", "t", null)))
			.isInstanceOfSatisfying(DeliveryException.class, e -> {
				assertThat(e.code()).isEqualTo("resend_http_403");
				assertThat(e.getMessage()).doesNotContain("a@example.com");
			});
	}

	@Test
	void refusesToStartWithoutKeyOrSender() {
		assertThatThrownBy(() -> new ResendEmailProvider(props("", ""), RestClient.builder()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("RESEND_API_KEY");
	}

}
