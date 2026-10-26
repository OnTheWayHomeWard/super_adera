package app.adera.registry.app;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MiniAppRepository extends JpaRepository<MiniApp, UUID> {

	Optional<MiniApp> findBySlug(String slug);

	boolean existsBySlug(String slug);

	List<MiniApp> findByStatusOrderByPriorityAscSlugAsc(MiniApp.Status status);

	List<MiniApp> findAllByOrderByPriorityAscSlugAsc(Pageable pageable);

	/** Recomputes the cached rating from the visible reviews only (hidden ones do not count). */
	@Modifying
	@Query(value = """
			UPDATE mini_app SET
			  rating_avg = COALESCE((SELECT ROUND(AVG(rating)::numeric, 2) FROM app_review WHERE app_id = :id AND status = 'VISIBLE'), 0),
			  rating_count = (SELECT COUNT(*) FROM app_review WHERE app_id = :id AND status = 'VISIBLE')
			WHERE id = :id""", nativeQuery = true)
	void refreshRating(@Param("id") UUID id);

}
