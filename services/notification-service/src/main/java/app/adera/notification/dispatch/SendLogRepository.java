package app.adera.notification.dispatch;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SendLogRepository extends JpaRepository<SendLog, UUID> {

	/** Attempts that count toward a recipient's limit (suppressed/skipped ones do not). */
	long countByChannelAndRecipientHashAndStatusInAndCreatedAtAfter(SendLog.Channel channel, String recipientHash,
			Collection<SendLog.Status> statuses, Instant after);

}
