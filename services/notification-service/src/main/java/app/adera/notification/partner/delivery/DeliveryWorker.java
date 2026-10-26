package app.adera.notification.partner.delivery;

import java.util.List;
import java.util.UUID;

import app.adera.notification.partner.PartnerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background delivery: every second, claim due notifications and deliver them. Claiming is atomic ("SKIP LOCKED") so any
 * number of service instances can run workers without sending a message twice, and a worker that dies mid-delivery has its
 * claim expire after 60 seconds and the notification picked up again (each claim counts as an attempt, so a message that keeps
 * killing workers is failed after {@link RetryPolicy#MAX_DELIVERY_ATTEMPTS} claims instead of looping).
 */
@Component
public class DeliveryWorker {

	private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
	static final int BATCH = 20;

	private final JdbcClient jdbc;
	private final DeliveryService delivery;
	private final PartnerProperties props;

	public DeliveryWorker(JdbcClient jdbc, DeliveryService delivery, PartnerProperties props) {
		this.jdbc = jdbc;
		this.delivery = delivery;
		this.props = props;
	}

	@Scheduled(fixedDelay = 1000)
	void tick() {
		if (!props.workersEnabled()) {
			return;
		}
		try {
			runOnce();
		}
		catch (RuntimeException e) {
			log.error("delivery worker tick failed: {}", e.toString());
		}
	}

	/** @return how many notifications were handled */
	public int runOnce() {
		List<UUID> ids = jdbc.sql("""
				UPDATE notification SET status = 'PROCESSING', attempts = attempts + 1, locked_until = now() + interval '60 seconds', updated_at = now()
				WHERE id IN (
				  SELECT id FROM notification
				  WHERE (status IN ('QUEUED', 'RETRYING') AND next_attempt_at <= now()) OR (status = 'PROCESSING' AND locked_until < now())
				  ORDER BY next_attempt_at LIMIT :n FOR UPDATE SKIP LOCKED)
				RETURNING id""").param("n", BATCH).query(UUID.class).list();
		for (UUID id : ids) {
			try {
				delivery.deliver(id);
			}
			catch (RuntimeException e) {
				// unexpected (a bug or a dependency): count it as a failed attempt and back off like any other failure, so a message
				// that keeps blowing up is neither re-claimed every minute nor retried forever
				log.error("delivering {} failed unexpectedly: {}", id, e.toString());
				try {
					delivery.recordUnexpectedFailure(id);
				}
				catch (RuntimeException again) {
					log.error("could not reschedule {} either: {}", id, again.toString());
				}
			}
		}
		return ids.size();
	}

}
