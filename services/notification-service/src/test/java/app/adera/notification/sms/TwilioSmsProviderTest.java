package app.adera.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.dispatch.DeliveryException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TwilioSmsProviderTest {

	private static NotificationProperties props(String messagingSid, String phone) {
		return new NotificationProperties("twilio", "mailpit", false, java.util.List.of(),
				new NotificationProperties.Limits(5, 10, 10, 20),
				new NotificationProperties.Twilio("https://api.twilio.com", "ACtest", "secret", messagingSid, phone),
				new NotificationProperties.Resend("", "", "", "Adera"),
				new NotificationProperties.Mailpit("no-reply@adera.local", "Adera"));
	}

	@Test
	void postsFormWithBasicAuthAndMessagingService() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/ACtest/Messages.json"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Basic QUN0ZXN0OnNlY3JldA=="))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().string(org.hamcrest.Matchers.allOf(
					org.hamcrest.Matchers.containsString("To=%2B251911234567"),
					org.hamcrest.Matchers.containsString("MessagingServiceSid=MG123"),
					org.hamcrest.Matchers.containsString("Body=Bole+-%3E+Airport"),
					org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("From=")))))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		new TwilioSmsProvider(props("MG123", "+15550000000"), builder).send("+251911234567", "Bole → Airport");
		server.verify();
	}

	@Test
	void fallsBackToSenderNumberWithoutMessagingService() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/ACtest/Messages.json"))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("From=%2B13214755233")))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		new TwilioSmsProvider(props("", "+13214755233"), builder).send("+251911234567", "code");
		server.verify();
	}

	@Test
	void httpErrorBecomesDeliveryExceptionWithoutLeakingBodyOrNumber() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer.bindTo(builder).build()
			.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/ACtest/Messages.json"))
			.andRespond(withServerError().body("secret detail +251911234567"));

		var provider = new TwilioSmsProvider(props("MG123", ""), builder);
		assertThatThrownBy(() -> provider.send("+251911234567", "code"))
			.isInstanceOfSatisfying(DeliveryException.class, e -> {
				assertThat(e.code()).isEqualTo("twilio_http_500");
				assertThat(e.getMessage()).doesNotContain("251911234567").doesNotContain("secret detail");
			});
	}

	@Test
	void refusesToStartWithoutCredentials() {
		var noCreds = new NotificationProperties("twilio", "mailpit", false, java.util.List.of(),
				new NotificationProperties.Limits(5, 10, 10, 20),
				new NotificationProperties.Twilio("https://api.twilio.com", "", "", "", ""),
				new NotificationProperties.Resend("", "", "", "Adera"),
				new NotificationProperties.Mailpit("no-reply@adera.local", "Adera"));
		assertThatThrownBy(() -> new TwilioSmsProvider(noCreds, RestClient.builder()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TWILIO_ACCOUNT_SID");
	}

}
