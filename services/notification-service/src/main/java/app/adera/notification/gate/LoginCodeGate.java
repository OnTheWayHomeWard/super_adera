package app.adera.notification.gate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.dispatch.Masking;
import app.adera.notification.dispatch.SendLog;
import app.adera.notification.partner.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells a sign-in screen, BEFORE it starts Kratos' send-code step, whether a code for this address would be delivered.
 *
 * <p>Why it exists: the real per-recipient limits (see NotificationDispatcher) drop messages silently (the courier answers
 * 200 so Kratos does not retry), and Kratos' flow API cannot tell the client, so a screen said "we sent a code" although
 * nothing was delivered. This gate counts the REQUESTS for an address, whatever the address is, and refuses the request once
 * the same hourly or daily limit is reached, with the time to wait. Because every address is counted the same way (an
 * unknown one exactly like a registered one), the answer reveals nothing about which accounts exist.
 *
 * <p>It is advisory: a client that calls Kratos directly skips it, and the dispatcher still enforces the limit (silently).
 */
@Service
public class LoginCodeGate {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}$");
	private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");

	public record Verdict(boolean allowed, int limitPerHour, int limitPerDay, int remainingThisHour, int remainingToday, long retryAfterSeconds) {
	}

	private final JdbcClient jdbc;
	private final NotificationProperties.Limits limits;
	private final Clock clock;

	public LoginCodeGate(JdbcClient jdbc, NotificationProperties props, Clock clock) {
		this.jdbc = jdbc;
		this.limits = props.limits();
		this.clock = clock;
	}

	@Transactional
	public Verdict check(String identifier) {
		String raw = identifier == null ? "" : identifier.trim();
		SendLog.Channel channel;
		String recipient;
		if (raw.contains("@")) {
			if (!EMAIL.matcher(raw).matches()) {
				throw ApiException.validation("identifier", "must be an email address or a phone number like +251911234567");
			}
			channel = SendLog.Channel.EMAIL;
			recipient = raw;
		}
		else {
			String phone = raw.replaceAll("[\\s()-]", "");
			if (!PHONE.matcher(phone).matches()) {
				throw ApiException.validation("identifier", "must be an email address or a phone number like +251911234567");
			}
			channel = SendLog.Channel.SMS;
			recipient = phone;
		}
		int perHour = channel == SendLog.Channel.EMAIL ? limits.emailPerHour() : limits.smsPerHour();
		int perDay = channel == SendLog.Channel.EMAIL ? limits.emailPerDay() : limits.smsPerDay();
		String hash = Masking.hash(recipient);
		Instant now = clock.instant();

		jdbc.sql("DELETE FROM code_request WHERE created_at < :before").param("before", Timestamp.from(now.minus(Duration.ofDays(2)))).update();
		List<Instant> day = jdbc.sql("SELECT created_at FROM code_request WHERE channel = :c AND recipient_hash = :h AND created_at > :since ORDER BY created_at")
			.param("c", channel.name()).param("h", hash).param("since", Timestamp.from(now.minus(Duration.ofDays(1))))
			.query((rs, n) -> rs.getTimestamp("created_at").toInstant()).list();
		List<Instant> hour = day.stream().filter(t -> t.isAfter(now.minus(Duration.ofHours(1)))).toList();

		long wait = Math.max(waitFor(hour, perHour, Duration.ofHours(1), now), waitFor(day, perDay, Duration.ofDays(1), now));
		if (wait > 0) {
			return new Verdict(false, perHour, perDay, 0, Math.max(0, perDay - day.size()), wait);
		}
		jdbc.sql("INSERT INTO code_request (id, channel, recipient_hash, created_at) VALUES (:id, :c, :h, :at)")
			.param("id", UUID.randomUUID()).param("c", channel.name()).param("h", hash).param("at", Timestamp.from(now)).update();
		return new Verdict(true, perHour, perDay, perHour - hour.size() - 1, perDay - day.size() - 1, 0);
	}

	/** Seconds until one more request fits in the window, or 0 when it fits now. {@code times} are the requests in the window, oldest first. */
	private static long waitFor(List<Instant> times, int limit, Duration window, Instant now) {
		if (times.size() < limit) {
			return 0;
		}
		Instant freesUp = times.get(times.size() - limit).plus(window);
		return Math.max(1, Duration.between(now, freesUp).toSeconds() + 1);
	}

}
