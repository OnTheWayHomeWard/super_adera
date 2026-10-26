package app.adera.notification.dispatch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

/**
 * In-memory copy of messages handled by NON-real providers, so tests can read one-time codes.
 * Messages sent by real providers are never stored here.
 */
@Component
public class DevMessageStore {

	public record DevMessage(Instant at, String channel, String to, String subject, String body) {
	}

	private static final int MAX = 100;

	private final ConcurrentLinkedDeque<DevMessage> messages = new ConcurrentLinkedDeque<>();

	public void add(DevMessage message) {
		messages.addFirst(message);
		while (messages.size() > MAX) {
			messages.pollLast();
		}
	}

	/** Newest first. */
	public List<DevMessage> list() {
		return new ArrayList<>(messages);
	}

	public void clear() {
		messages.clear();
	}

}
