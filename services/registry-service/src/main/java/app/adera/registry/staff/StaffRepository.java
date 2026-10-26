package app.adera.registry.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.adera.registry.auth.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffRepository extends JpaRepository<Staff, UUID> {

	List<Staff> findAllByOrderByCreatedAtAsc();

	Optional<Staff> findByEmailIgnoreCase(String email);

	long countByRoleAndStatus(Role role, Staff.Status status);

}
