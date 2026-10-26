package app.adera.registry.staff;

import app.adera.registry.auth.Role;
import app.adera.registry.config.RegistryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first super admin from BOOTSTRAP_SUPER_ADMIN_EMAIL when none exists yet, so a fresh install can be
 * signed into. Retries in the background (Kratos may still be starting) without blocking startup.
 */
@Component
public class StaffBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

	private final RegistryProperties props;
	private final StaffRepository staff;
	private final StaffService service;

	public StaffBootstrap(RegistryProperties props, StaffRepository staff, StaffService service) {
		this.props = props;
		this.staff = staff;
		this.service = service;
	}

	@Override
	public void run(ApplicationArguments args) {
		String email = props.bootstrapSuperAdminEmail();
		if (email == null || email.isBlank()) {
			return;
		}
		Thread thread = new Thread(() -> bootstrap(email), "staff-bootstrap");
		thread.setDaemon(true);
		thread.start();
	}

	private void bootstrap(String email) {
		for (int attempt = 1; attempt <= 20; attempt++) {
			try {
				if (staff.countByRoleAndStatus(Role.SUPER_ADMIN, Staff.Status.ACTIVE) > 0) {
					return;
				}
				service.ensure(email, Role.SUPER_ADMIN, null);
				log.warn("Created the first super admin from BOOTSTRAP_SUPER_ADMIN_EMAIL. Sign in with that email, enrol an authenticator app, then change it.");
				return;
			}
			catch (RuntimeException e) {
				log.info("first super admin not created yet (attempt {}): {}", attempt, e.getMessage());
				try {
					Thread.sleep(3000);
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
		log.error("could not create the first super admin after 20 attempts; check Kratos");
	}

}
