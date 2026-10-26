package app.adera.registry.audit;

import app.adera.registry.auth.Role;
import java.util.UUID;

/** The staff member performing an admin action (from the gateway-verified headers) and where they are calling from. */
public record Actor(UUID id, Role role, String ip) {
}
