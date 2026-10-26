package app.adera.user.device;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.adera.user.profile.ProfileService;
import app.adera.user.profile.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tracks which installations can receive push for a user. Rules (modelled on how ride_flow handled FCM):
 * <ul>
 * <li>an FCM token is unique: registering a token that exists moves it to the current user (one phone, two accounts
 * over time) and re-activates it;</li>
 * <li>when the app supplies a stable installation id, a rotated token <em>replaces</em> the old one instead of piling
 * up a dead token per rotation;</li>
 * <li>logout removes the token so the next person on the phone does not receive the previous user's pushes.</li>
 * </ul>
 */
@Service
public class DeviceService {

	private final DeviceRepository devices;
	private final ProfileService profiles;
	private final Clock clock;

	public DeviceService(DeviceRepository devices, ProfileService profiles, Clock clock) {
		this.devices = devices;
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional
	public Device register(UUID userId, DeviceDtos.RegisterRequest request) {
		profiles.getOrCreate(userId); // the device row references the user
		Instant now = clock.instant();
		String installationId = blankToNull(request.installationId());

		Optional<Device> byInstallation = installationId == null ? Optional.empty()
				: devices.findByUserIdAndInstallationId(userId, installationId);
		Optional<Device> byToken = devices.findByFcmToken(request.fcmToken());

		Device device;
		if (byInstallation.isPresent()) {
			device = byInstallation.get();
			// the new token may currently belong to another row (old account, or an earlier registration): free it first
			if (byToken.isPresent() && !byToken.get().getId().equals(device.getId())) {
				devices.delete(byToken.get());
				devices.flush();
			}
		}
		else if (byToken.isPresent()) {
			device = byToken.get();
		}
		else {
			device = new Device(userId, now);
		}
		device.update(userId, installationId, request.platform(), request.fcmToken(), blankToNull(request.appVersion()),
				blankToNull(request.osVersion()), blankToNull(request.deviceName()), blankToNull(request.locale()), now);
		return devices.save(device);
	}

	@Transactional(readOnly = true)
	public List<Device> list(UUID userId) {
		return devices.findByUserIdAndActiveTrueOrderByLastSeenAtDesc(userId);
	}

	/** @return how many devices were removed */
	@Transactional
	public long remove(UUID userId, DeviceDtos.RemoveRequest request) {
		if (request == null || request.isEmpty()) {
			throw new ValidationFailedException("fcmToken", "give fcmToken or installationId");
		}
		long removed = 0;
		if (request.fcmToken() != null && !request.fcmToken().isBlank()) {
			removed += devices.deleteByUserIdAndFcmToken(userId, request.fcmToken());
		}
		if (request.installationId() != null && !request.installationId().isBlank()) {
			removed += devices.deleteByUserIdAndInstallationId(userId, request.installationId());
		}
		return removed;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

}
