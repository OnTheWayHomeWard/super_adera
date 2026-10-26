package app.adera.notification.partner.admin;

import java.util.UUID;

/** Who is making an admin change (taken from the headers the gateway sets after checking the staff session). */
public record StaffActor(UUID id, StaffRole role, String ip) {
}
