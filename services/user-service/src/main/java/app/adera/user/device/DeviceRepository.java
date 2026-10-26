package app.adera.user.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

	Optional<Device> findByFcmToken(String fcmToken);

	Optional<Device> findByUserIdAndInstallationId(UUID userId, String installationId);

	List<Device> findByUserIdAndActiveTrueOrderByLastSeenAtDesc(UUID userId);

	long deleteByUserIdAndFcmToken(UUID userId, String fcmToken);

	long deleteByUserIdAndInstallationId(UUID userId, String installationId);

}
