package app.adera.user.profile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

	Optional<AppUser> findByIdAndDeletedAtIsNull(UUID id);

	/**
	 * Creates the row unless it already exists. A plain save() would race: the Kratos webhook and the user's first
	 * API call can arrive at the same moment, and the loser must not fail or poison its transaction.
	 *
	 * @return 1 if this call created the row, 0 if it already existed
	 */
	@Modifying
	@Query(value = """
			INSERT INTO app_user (id, email, phone_e164, market, created_at, updated_at)
			VALUES (:id, :email, :phone, :market, :now, :now)
			ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("email") String email, @Param("phone") String phone,
			@Param("market") String market, @Param("now") Instant now);

	/** Status and deleted-at of one user, without loading the whole row (called on every gateway verify). */
	@Query("SELECT u.status AS status, u.deletedAt AS deletedAt FROM AppUser u WHERE u.id = :id")
	Optional<StatusView> findStatusById(@Param("id") UUID id);

	interface StatusView {

		AppUser.Status getStatus();

		Instant getDeletedAt();

	}

}
