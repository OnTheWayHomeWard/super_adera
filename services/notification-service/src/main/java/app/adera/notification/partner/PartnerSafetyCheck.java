package app.adera.notification.partner;

import app.adera.notification.email.EmailProvider;
import app.adera.notification.partner.delivery.FcmProvider;
import app.adera.notification.sms.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup guard: refuses to let callback-address checks be relaxed while anything real is switched on. */
@Component
public class PartnerSafetyCheck {

	private static final Logger log = LoggerFactory.getLogger(PartnerSafetyCheck.class);

	private final PartnerProperties props;
	private final FcmProvider fcm;
	private final boolean anyReal;

	public PartnerSafetyCheck(PartnerProperties props, SmsProvider sms, EmailProvider email, FcmProvider fcm) {
		this.props = props;
		this.fcm = fcm;
		this.anyReal = sms.isReal() || email.isReal() || fcm.isReal();
		if (props.allowPrivateCallbacks() && anyReal) {
			throw new IllegalStateException("PARTNER_ALLOW_PRIVATE_CALLBACKS=true lets callbacks reach private addresses, but a real provider is enabled. Turn it off.");
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	void announce() {
		log.warn("PARTNER PLATFORM: push={} ({}), workers={}, privateCallbacks={}", fcm.name(), fcm.isReal() ? "REAL" : "dev", props.workersEnabled(),
				props.allowPrivateCallbacks());
	}

}
