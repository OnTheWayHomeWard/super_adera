package app.adera.notification.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "adera.notification")
public record NotificationProperties(
		@DefaultValue("console") String smsProvider,
		@DefaultValue("mailpit") String emailProvider,
		@DefaultValue("false") boolean devEndpoints,
		@DefaultValue List<String> liveAllowlist,
		@DefaultValue Limits limits,
		@DefaultValue Twilio twilio,
		@DefaultValue Resend resend,
		@DefaultValue Mailpit mailpit) {

	public record Limits(
			@DefaultValue("5") int smsPerHour,
			@DefaultValue("10") int smsPerDay,
			@DefaultValue("10") int emailPerHour,
			@DefaultValue("20") int emailPerDay) {
	}

	public record Twilio(
			@DefaultValue("https://api.twilio.com") String baseUrl,
			@DefaultValue("") String accountSid,
			@DefaultValue("") String authToken,
			@DefaultValue("") String messagingServiceSid,
			@DefaultValue("") String phoneNumber) {
	}

	public record Resend(
			@DefaultValue("https://api.resend.com") String baseUrl,
			@DefaultValue("") String apiKey,
			@DefaultValue("") String fromEmail,
			@DefaultValue("Adera") String fromName) {
	}

	public record Mailpit(
			@DefaultValue("no-reply@adera.local") String fromEmail,
			@DefaultValue("Adera") String fromName) {
	}

}
