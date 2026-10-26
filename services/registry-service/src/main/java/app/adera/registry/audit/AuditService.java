package app.adera.registry.audit;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

	private final AuditRepository repository;
	private final Clock clock;

	public AuditService(AuditRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** Joins the caller's transaction, so a change and its audit row commit (or roll back) together. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Actor actor, String action, String targetType, String targetId, Map<String, Object> meta) {
		repository.save(new AuditEvent(actor == null ? null : actor.id(), actor == null || actor.role() == null ? null : actor.role().name(),
				action, targetType, targetId, meta, actor == null ? null : actor.ip(), clock.instant()));
	}

	@Transactional(readOnly = true)
	public List<AuditEvent> latest(int limit, String targetId) {
		PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
		return targetId == null || targetId.isBlank() ? repository.findAllByOrderByAtDesc(page)
				: repository.findByTargetIdOrderByAtDesc(targetId, page);
	}

	/** For other services (via /internal/audit): same log, own transaction. Returns the new row's id. */
	@Transactional
	public UUID recordExternal(UUID actorId, String actorRole, String action, String targetType, String targetId, Map<String, Object> meta, String ip) {
		AuditEvent event = new AuditEvent(actorId, actorRole, action, targetType, targetId, meta, ip, clock.instant());
		repository.save(event);
		return event.getId();
	}

}
