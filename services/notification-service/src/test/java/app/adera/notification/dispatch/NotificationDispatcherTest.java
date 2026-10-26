package app.adera.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.email.EmailProvider;
import app.adera.notification.sms.SmsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDispatcherTest {

	SmsProvider sms = mock(SmsProvider.class);
	EmailProvider email = mock(EmailProvider.class);
	SendLogRepository logs = mock(SendLogRepository.class);
	DevMessageStore dev = new DevMessageStore();
	Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

	@BeforeEach
	void setUp() {
		when(sms.name()).thenReturn("twilio");
		when(email.name()).thenReturn("mailpit");
	}

	private NotificationDispatcher dispatcher(List<String> allowlist) {
		var props = new NotificationProperties("twilio", "mailpit", false, allowlist,
				new NotificationProperties.Limits(2, 5, 10, 20),
				new NotificationProperties.Twilio("", "", "", "", ""),
				new NotificationProperties.Resend("", "", "", "Adera"),
				new NotificationProperties.Mailpit("no-reply@adera.local", "Adera"));
		return new NotificationDispatcher(sms, email, logs, props, dev, clock);
	}

	private SendLog lastSaved() {
		ArgumentCaptor<SendLog> captor = ArgumentCaptor.forClass(SendLog.class);
		verify(logs).save(captor.capture());
		return captor.getValue();
	}

	@Test
	void realProviderSendsAndLogsWithoutStoringForDev() {
		when(sms.isReal()).thenReturn(true);
		var result = dispatcher(List.of()).sendSms("+251911234567", "code 1", "login_code_valid");

		assertThat(result.status()).isEqualTo(SendLog.Status.SENT);
		verify(sms).send("+251911234567", "code 1");
		assertThat(dev.list()).as("real messages must never be kept for dev tools").isEmpty();
		assertThat(lastSaved().getStatus()).isEqualTo(SendLog.Status.SENT);
	}

	@Test
	void nonRealProviderIsKeptForDevTools() {
		when(sms.isReal()).thenReturn(false);
		dispatcher(List.of()).sendSms("+251911234567", "code 1", null);
		assertThat(dev.list()).singleElement().satisfies(m -> assertThat(m.body()).isEqualTo("code 1"));
	}

	@Test
	void overTheHourlyLimitIsSuppressedWithoutCallingTheProvider() {
		when(sms.isReal()).thenReturn(true);
		when(logs.countByChannelAndRecipientHashAndStatusInAndCreatedAtAfter(eq(SendLog.Channel.SMS), any(), any(),
				eq(clock.instant().minusSeconds(3600)))).thenReturn(2L);

		var result = dispatcher(List.of()).sendSms("+251911234567", "code", null);

		assertThat(result.status()).isEqualTo(SendLog.Status.SUPPRESSED);
		verify(sms, never()).send(any(), any());
		assertThat(lastSaved().getErrorCode()).isEqualTo("rate_limited");
	}

	@Test
	void allowlistBlocksOthersOnRealProviders() {
		when(sms.isReal()).thenReturn(true);
		var d = dispatcher(List.of("+251911111111"));

		assertThat(d.sendSms("+251922222222", "code", null).status()).isEqualTo(SendLog.Status.SKIPPED);
		verify(sms, never()).send(any(), any());

		assertThat(d.sendSms("+251911111111", "code", null).status()).isEqualTo(SendLog.Status.SENT);
		verify(sms).send("+251911111111", "code");
	}

	@Test
	void allowlistDoesNotApplyToDevProviders() {
		when(sms.isReal()).thenReturn(false);
		var result = dispatcher(List.of("+251911111111")).sendSms("+251922222222", "code", null);
		assertThat(result.status()).isEqualTo(SendLog.Status.SENT);
	}

	@Test
	void providerFailureIsLoggedAsFailedAndRethrown() {
		when(sms.isReal()).thenReturn(true);
		doThrow(new DeliveryException("twilio_http_503", null)).when(sms).send(any(), any());

		assertThatThrownBy(() -> dispatcher(List.of()).sendSms("+251911234567", "code", null))
			.isInstanceOf(DeliveryException.class);
		SendLog saved = lastSaved();
		assertThat(saved.getStatus()).isEqualTo(SendLog.Status.FAILED);
		assertThat(saved.getErrorCode()).isEqualTo("twilio_http_503");
		assertThat(dev.list()).isEmpty();
	}

}
