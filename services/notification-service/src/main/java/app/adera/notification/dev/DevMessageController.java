package app.adera.notification.dev;

import java.util.List;

import app.adera.notification.dispatch.DevMessageStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only: shows what the non-real providers "sent", so tests can read one-time codes.
 * Only exists when {@code adera.notification.dev-endpoints=true}, and {@link ProviderSafetyCheck} refuses
 * to start the service if that is combined with a real provider.
 */
@RestController
@RequestMapping("/dev/messages")
@ConditionalOnProperty(prefix = "adera.notification", name = "dev-endpoints", havingValue = "true")
public class DevMessageController {

	private final DevMessageStore store;

	public DevMessageController(DevMessageStore store) {
		this.store = store;
	}

	@GetMapping
	public List<DevMessageStore.DevMessage> list(@RequestParam(required = false) String to) {
		return store.list().stream().filter(m -> to == null || to.equalsIgnoreCase(m.to())).toList();
	}

	@DeleteMapping
	public void clear() {
		store.clear();
	}

}
