package app.adera.notification.partner.template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

	List<Template> findByAppIdOrderByCodeAsc(UUID appId);

	Optional<Template> findByIdAndAppId(UUID id, UUID appId);

	boolean existsByAppIdAndCode(UUID appId, String code);

}
