package app.adera.notification.sms;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.dispatch.DeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends SMS through Twilio's REST API. Prefers the campaign-linked Messaging Service (needed for reliable
 * US A2P 10DLC delivery) and falls back to a plain sender number.
 */
@Component
@ConditionalOnProperty(prefix = "adera.notification", name = "sms-provider", havingValue = "twilio")
public class TwilioSmsProvider implements SmsProvider {

	private final NotificationProperties.Twilio config;
	private final RestClient client;

	public TwilioSmsProvider(NotificationProperties props, RestClient.Builder builder) {
		this.config = props.twilio();
		if (blank(config.accountSid()) || blank(config.authToken())) {
			throw new IllegalStateException(
					"sms-provider=twilio but TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN are not set (check Infisical /notification)");
		}
		if (blank(config.messagingServiceSid()) && blank(config.phoneNumber())) {
			throw new IllegalStateException(
					"sms-provider=twilio needs TWILIO_MESSAGING_SERVICE_SID or TWILIO_PHONE_NUMBER");
		}
		this.client = builder.baseUrl(config.baseUrl()).build();
	}

	@Override
	public String name() {
		return "twilio";
	}

	@Override
	public boolean isReal() {
		return true;
	}

	@Override
	public void send(String to, String body) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("To", to);
		form.add("Body", GsmText.toGsmSafe(body));
		if (!blank(config.messagingServiceSid())) {
			form.add("MessagingServiceSid", config.messagingServiceSid());
		}
		else {
			form.add("From", config.phoneNumber());
		}
		try {
			client.post()
				.uri("/2010-04-01/Accounts/{sid}/Messages.json", config.accountSid())
				.headers(h -> h.setBasicAuth(config.accountSid(), config.authToken()))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientResponseException e) {
			// Never include the response body or the request: it can contain the code or the number.
			// e.g. 21610 = recipient replied STOP; surfaced as the HTTP status only.
			throw new DeliveryException("twilio_http_" + e.getStatusCode().value(), e);
		}
		catch (RestClientException e) {
			throw new DeliveryException("twilio_unreachable", e);
		}
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

}
