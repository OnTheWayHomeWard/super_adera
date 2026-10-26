package app.adera.notification.partner.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.dispatch.DeliveryException;
import app.adera.notification.dispatch.Masking;
import app.adera.notification.email.EmailMessage;
import app.adera.notification.email.EmailProvider;
import app.adera.notification.partner.UpstreamUnavailableException;
import app.adera.notification.partner.auth.UserDirectoryClient;
import app.adera.notification.partner.template.Template;
import app.adera.notification.partner.template.TemplateService;
import app.adera.notification.sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers one queued notification: re-checks the user (their phone, email and mute may have changed since the request),
 * renders the template in their language, sends through the channel chosen at acceptance, and records the outcome.
 *
 * <p>Outcomes: SENT; FAILED (permanent: bad number or address, never retried); UNREACHABLE (account deleted or suspended);
 * SUPPRESSED (user muted the app, or the live allowlist excluded them); EXPIRED (kept trying until the deadline);
 * otherwise RETRYING with backoff.
 */
@Service
public class DeliveryService {

	private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

	private final NotificationRepository repository;
	private final TemplateService templates;
	private final UserDirectoryClient directory;
	private final NotificationStore store;
	private final SmsProvider sms;
	private final EmailProvider email;
	private final FcmProvider fcm;
	private final Set<String> allowlist;
	private final Clock clock;
	private final JsonMapper mapper;

	public DeliveryService(NotificationRepository repository, TemplateService templates, UserDirectoryClient directory,
			NotificationStore store, SmsProvider sms, EmailProvider email, FcmProvider fcm, NotificationProperties props, Clock clock, JsonMapper mapper) {
		this.mapper = mapper;
		this.repository = repository;
		this.templates = templates;
		this.directory = directory;
		this.store = store;
		this.sms = sms;
		this.email = email;
		this.fcm = fcm;
		this.allowlist = props.liveAllowlist().stream().map(Masking::normalise).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
		this.clock = clock;
	}

	public void deliver(UUID id) {
		Notification n = repository.findById(id).orElse(null);
		if (n == null || n.isTerminal()) {
			return;
		}
		Instant now = clock.instant();
		if (now.isAfter(n.getExpiresAt())) {
			store.markTerminal(id, Notification.EXPIRED, n.getReason() == null ? "expired" : n.getReason());
			return;
		}
		// DEV only: the synthetic users (u_p_test_*) are not real people, so nothing is sent for them. Everyone else is delivered
		// through the dev providers (console SMS, Mailpit), which are the sandbox.
		if ("DEV".equals(n.getEnvironment()) && n.getUserAlias().startsWith(NotificationAcceptor.SYNTHETIC_PREFIX)) {
			store.recordAttempt(id, n.getChannel(), "sandbox", "OK", null);
			store.markSent(id);
			return;
		}
		UserDirectoryClient.DeliveryContext user;
		try {
			user = directory.context(n.getUserId(), n.getAppId()).orElse(null);
		}
		catch (UpstreamUnavailableException e) {
			retryOrGiveUp(n, "user_directory_unavailable", false);
			return;
		}
		if (user == null || !user.reachable()) {
			store.markTerminal(id, Notification.UNREACHABLE, "user_unreachable");
			return;
		}
		boolean muted = switch (n.getChannel()) {
			case "SMS" -> user.muted().muteSms();
			case "EMAIL" -> user.muted().muteEmail();
			default -> user.muted().muteInApp();
		};
		if (muted) {
			store.markTerminal(id, Notification.SUPPRESSED, "recipient_opted_out");
			return;
		}
		Template template = templates.get(n.getTemplateId());
		TemplateService.Rendered text = templates.render(template, user.language(), n.getParams());
		switch (n.getChannel()) {
			case "SMS" -> sendSms(n, user, text);
			case "EMAIL" -> sendEmail(n, user, text);
			default -> sendInApp(n, user, text);
		}
	}

	private void sendSms(Notification n, UserDirectoryClient.DeliveryContext user, TemplateService.Rendered text) {
		if (user.phone() == null || user.phone().isBlank()) {
			store.markTerminal(n.getId(), Notification.UNREACHABLE, "no_phone");
			return;
		}
		if (blockedByAllowlist(sms.isReal(), user.phone())) {
			store.recordAttempt(n.getId(), "SMS", sms.name(), "SKIPPED", "not_allowlisted");
			store.markTerminal(n.getId(), Notification.SUPPRESSED, "not_allowlisted");
			return;
		}
		try {
			sms.send(user.phone(), text.sms());
			store.recordAttempt(n.getId(), "SMS", sms.name(), "OK", null);
			store.markSent(n.getId());
		}
		catch (DeliveryException e) {
			failed(n, "SMS", sms.name(), e);
		}
	}

	private void sendEmail(Notification n, UserDirectoryClient.DeliveryContext user, TemplateService.Rendered text) {
		if (user.email() == null || user.email().isBlank()) {
			store.markTerminal(n.getId(), Notification.UNREACHABLE, "no_email");
			return;
		}
		if (blockedByAllowlist(email.isReal(), user.email())) {
			store.recordAttempt(n.getId(), "EMAIL", email.name(), "SKIPPED", "not_allowlisted");
			store.markTerminal(n.getId(), Notification.SUPPRESSED, "not_allowlisted");
			return;
		}
		try {
			email.send(new EmailMessage(user.email(), text.emailSubject(), text.body(), text.emailHtml()));
			store.recordAttempt(n.getId(), "EMAIL", email.name(), "OK", null);
			store.markSent(n.getId());
		}
		catch (DeliveryException e) {
			failed(n, "EMAIL", email.name(), e);
		}
	}

	/**
	 * The inbox entry is the reliable part (the user sees it when they open the app); push is best effort on top of it.
	 * Dead tokens are retired so we stop trying them.
	 */
	private void sendInApp(Notification n, UserDirectoryClient.DeliveryContext user, TemplateService.Rendered text) {
		store.addInbox(n.getId(), n.getUserId(), n.getAppId(), text.title(), text.body(), n.getData());
		if (user.pushTokens().isEmpty()) {
			store.recordAttempt(n.getId(), "IN_APP", fcm.name(), "SKIPPED", "no_devices");
		}
		Map<String, String> payload = PushPayload.build(n.getData(), n.getId(), n.getAppId(), mapper);
		for (String token : user.pushTokens()) {
			FcmProvider.PushResult result = fcm.send(token, text.title(), text.body(), payload);
			switch (result) {
				case OK -> store.recordAttempt(n.getId(), "IN_APP", fcm.name(), "OK", null);
				case UNREGISTERED -> {
					directory.deactivateToken(token);
					store.recordAttempt(n.getId(), "IN_APP", fcm.name(), "PERMANENT", "token_unregistered");
				}
				case TRANSIENT_FAILURE -> store.recordAttempt(n.getId(), "IN_APP", fcm.name(), "TRANSIENT", "push_unavailable");
				// our configuration or payload, not the device: the token stays active and the attempt shows as a provider error
				case REJECTED -> store.recordAttempt(n.getId(), "IN_APP", fcm.name(), "PERMANENT", "push_rejected");
			}
		}
		store.markSent(n.getId());
	}

	private boolean blockedByAllowlist(boolean real, String recipient) {
		return real && !allowlist.isEmpty() && !allowlist.contains(Masking.normalise(recipient));
	}

	private void failed(Notification n, String channel, String provider, DeliveryException e) {
		boolean permanent = RetryPolicy.isPermanent(e.code());
		store.recordAttempt(n.getId(), channel, provider, permanent ? "PERMANENT" : "TRANSIENT", e.code());
		log.warn("delivery attempt failed: notification={} channel={} provider={} code={} permanent={}", n.getId(), channel, provider, e.code(), permanent);
		if (permanent) {
			store.markTerminal(n.getId(), Notification.FAILED, e.code());
		}
		else {
			retryOrGiveUp(n, e.code(), true);
		}
	}

	/** {@code n.getAttempts()} already counts the attempt that just failed (it is incremented when the worker claims it). */
	private void retryOrGiveUp(Notification n, String reason, boolean attemptRecorded) {
		Instant now = clock.instant();
		Instant next = now.plus(RetryPolicy.deliveryDelay(n.getAttempts()));
		if (n.getAttempts() >= RetryPolicy.MAX_DELIVERY_ATTEMPTS) {
			store.markTerminal(n.getId(), Notification.FAILED, "max_attempts:" + reason);
		}
		else if (next.isAfter(n.getExpiresAt())) {
			store.markTerminal(n.getId(), Notification.EXPIRED, reason);
		}
		else {
			store.scheduleRetry(n.getId(), next, reason);
		}
	}

}
