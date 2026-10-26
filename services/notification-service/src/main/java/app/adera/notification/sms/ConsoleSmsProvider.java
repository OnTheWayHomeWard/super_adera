package app.adera.notification.sms;

import app.adera.notification.dispatch.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev provider: writes the SMS to the log instead of sending it. Never used with real credentials. */
@Component
@ConditionalOnProperty(prefix = "adera.notification", name = "sms-provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsProvider implements SmsProvider {

	private static final Logger log = LoggerFactory.getLogger(ConsoleSmsProvider.class);

	@Override
	public String name() {
		return "console";
	}

	@Override
	public boolean isReal() {
		return false;
	}

	@Override
	public void send(String to, String body) {
		log.info("[SMS console] to={} body={}", Masking.phone(to), body.replace('\n', ' '));
	}

}
