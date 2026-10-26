package app.adera.notification.email;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.dispatch.DeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Sends transactional email through Resend's REST API. The sender domain must be verified in Resend. */
@Component
@ConditionalOnProperty(prefix = "adera.notification", name = "email-provider", havingValue = "resend")
public class ResendEmailProvider implements EmailProvider {

	private final NotificationProperties.Resend config;
	private final RestClient client;

	public ResendEmailProvider(NotificationProperties props, RestClient.Builder builder) {
		this.config = props.resend();
		if (blank(config.apiKey()) || blank(config.fromEmail())) {
			throw new IllegalStateException(
					"email-provider=resend but RESEND_API_KEY / RESEND_FROM_EMAIL are not set (check Infisical /notification)");
		}
		this.client = builder.baseUrl(config.baseUrl()).build();
	}

	@Override
	public String name() {
		return "resend";
	}

	@Override
	public boolean isReal() {
		return true;
	}

	@Override
	public void send(EmailMessage message) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("from", config.fromName().isBlank() ? config.fromEmail()
				: config.fromName() + " <" + config.fromEmail() + ">");
		payload.put("to", List.of(message.to()));
		payload.put("subject", message.subject());
		if (message.text() != null && !message.text().isBlank()) {
			payload.put("text", message.text());
		}
		if (message.html() != null && !message.html().isBlank()) {
			payload.put("html", message.html());
		}
		try {
			client.post()
				.uri("/emails")
				.headers(h -> h.setBearerAuth(config.apiKey()))
				.contentType(MediaType.APPLICATION_JSON)
				.body(payload)
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientResponseException e) {
			throw new DeliveryException("resend_http_" + e.getStatusCode().value(), e);
		}
		catch (RestClientException e) {
			throw new DeliveryException("resend_unreachable", e);
		}
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

}
