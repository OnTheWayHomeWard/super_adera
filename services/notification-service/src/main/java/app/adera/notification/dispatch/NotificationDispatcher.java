package app.adera.notification.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.email.EmailMessage;
import app.adera.notification.email.EmailProvider;
import app.adera.notification.sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one place every outgoing message passes through:
 * per-recipient rate limit, then the live-mode allowlist, then the provider, then the send log.
 */
@Service
public class NotificationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

	private static final Set<SendLog.Status> COUNTED = EnumSet.of(SendLog.Status.SENT, SendLog.Status.FAILED);

	private final SmsProvider sms;
	private final EmailProvider email;
	private final SendLogRepository logs;
	private final NotificationProperties props;
	private final DevMessageStore devStore;
	private final Clock clock;
	private final Set<String> allowlist;

	public NotificationDispatcher(SmsProvider sms, EmailProvider email, SendLogRepository logs,
			NotificationProperties props, DevMessageStore devStore, Clock clock) {
		this.sms = sms;
		this.email = email;
		this.logs = logs;
		this.props = props;
		this.devStore = devStore;
		this.clock = clock;
		this.allowlist = props.liveAllowlist().stream()
			.map(Masking::normalise)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toUnmodifiableSet());
	}

	public DispatchResult sendSms(String to, String body, String templateType) {
		NotificationProperties.Limits limits = props.limits();
		return dispatch(SendLog.Channel.SMS, to, Masking.phone(to), templateType, sms.name(), sms.isReal(),
				limits.smsPerHour(), limits.smsPerDay(), () -> sms.send(to, body),
				() -> devStore.add(new DevMessageStore.DevMessage(clock.instant(), "sms", to, null, body)));
	}

	public DispatchResult sendEmail(EmailMessage message, String templateType) {
		NotificationProperties.Limits limits = props.limits();
		return dispatch(SendLog.Channel.EMAIL, message.to(), Masking.email(message.to()), templateType,
				email.name(), email.isReal(), limits.emailPerHour(), limits.emailPerDay(),
				() -> email.send(message),
				() -> devStore.add(new DevMessageStore.DevMessage(clock.instant(), "email", message.to(),
						message.subject(), message.text())));
	}

	private DispatchResult dispatch(SendLog.Channel channel, String recipient, String masked, String templateType,
			String provider, boolean real, int perHour, int perDay, Runnable send, Runnable recordForDev) {
		String hash = Masking.hash(recipient);
		Instant now = clock.instant();

		if (overLimit(channel, hash, now, perHour, perDay)) {
			log.warn("rate limit reached: channel={} to={}", channel, masked);
			return record(channel, hash, masked, templateType, provider, SendLog.Status.SUPPRESSED, "rate_limited", now);
		}
		if (real && !allowlist.isEmpty() && !allowlist.contains(Masking.normalise(recipient))) {
			log.warn("live allowlist active, not delivering: channel={} to={}", channel, masked);
			return record(channel, hash, masked, templateType, provider, SendLog.Status.SKIPPED, "not_allowlisted", now);
		}
		try {
			send.run();
		}
		catch (DeliveryException e) {
			record(channel, hash, masked, templateType, provider, SendLog.Status.FAILED, e.code(), now);
			log.error("delivery failed: channel={} provider={} to={} code={}", channel, provider, masked, e.code());
			throw e;
		}
		if (!real) {
			recordForDev.run();
		}
		return record(channel, hash, masked, templateType, provider, SendLog.Status.SENT, null, now);
	}

	private boolean overLimit(SendLog.Channel channel, String hash, Instant now, int perHour, int perDay) {
		return count(channel, hash, now.minus(Duration.ofHours(1))) >= perHour
				|| count(channel, hash, now.minus(Duration.ofDays(1))) >= perDay;
	}

	private long count(SendLog.Channel channel, String hash, Instant since) {
		return logs.countByChannelAndRecipientHashAndStatusInAndCreatedAtAfter(channel, hash, COUNTED, since);
	}

	private DispatchResult record(SendLog.Channel channel, String hash, String masked, String templateType,
			String provider, SendLog.Status status, String errorCode, Instant now) {
		logs.save(new SendLog(channel, hash, masked, templateType, provider, status, errorCode, now));
		return new DispatchResult(status, provider);
	}

}
