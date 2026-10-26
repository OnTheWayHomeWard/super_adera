package app.adera.registry.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

	List<AuditEvent> findAllByOrderByAtDesc(Pageable pageable);

	List<AuditEvent> findByTargetIdOrderByAtDesc(String targetId, Pageable pageable);

}
