package app.adera.notification.partner.delivery;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev stand-in for Firebase: logs the push instead of sending it, and keeps what it "sent" (tests read it).
 * Used until a Firebase service account is configured ({@code FCM_PROVIDER=firebase}).
 */
@Component
@ConditionalOnProperty(prefix = "adera.partner", name = "fcm-provider", havingValue = "fake", matchIfMissing = true)
public class FakeFcmProvider implements FcmProvider {

	public record Sent(String token, String title, String body, Map<String, String> data) {
	}

	private static final Logger log = LoggerFactory.getLogger(FakeFcmProvider.class);

	public final List<Sent> sent = new CopyOnWriteArrayList<>();

	@Override
	public String name() {
		return "fcm-fake";
	}

	@Override
	public boolean isReal() {
		return false;
	}

	@Override
	public PushResult send(String token, String title, String body, Map<String, String> data) {
		sent.add(new Sent(token, title, body, data));
		log.info("[PUSH fake] token=...{} title={}", token.length() > 6 ? token.substring(token.length() - 6) : token, title);
		return PushResult.OK;
	}

}
