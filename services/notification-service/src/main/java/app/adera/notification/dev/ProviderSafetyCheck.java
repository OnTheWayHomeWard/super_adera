package app.adera.notification.dev;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.email.EmailProvider;
import app.adera.notification.sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup guard: states the mode loudly, and refuses unsafe combinations. */
@Component
public class ProviderSafetyCheck {

	private static final Logger log = LoggerFactory.getLogger(ProviderSafetyCheck.class);

	private final SmsProvider sms;
	private final EmailProvider email;
	private final NotificationProperties props;

	public ProviderSafetyCheck(SmsProvider sms, EmailProvider email, NotificationProperties props) {
		this.sms = sms;
		this.email = email;
		this.props = props;
		if (props.devEndpoints() && (sms.isReal() || email.isReal())) {
			throw new IllegalStateException(
					"NOTIFY_DEV_ENDPOINTS=true would expose one-time codes, but a real provider is enabled (sms="
							+ sms.name() + ", email=" + email.name() + "). Turn dev endpoints off.");
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	void announce() {
		log.warn("NOTIFICATION MODE: sms={} ({}), email={} ({}), allowlist={}, devEndpoints={}",
				sms.name(), sms.isReal() ? "REAL, costs money" : "dev, nothing leaves the machine",
				email.name(), email.isReal() ? "REAL" : "dev, nothing leaves the machine",
				props.liveAllowlist().isEmpty() ? "off" : props.liveAllowlist().size() + " recipient(s)",
				props.devEndpoints());
	}

}
