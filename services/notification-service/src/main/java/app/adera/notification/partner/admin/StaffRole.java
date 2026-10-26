package app.adera.notification.partner.admin;

import app.adera.notification.partner.ApiException;

/** Dashboard roles (the same three as registry-service): higher rank includes everything below it. */
public enum StaffRole {

	VIEWER(1), ADMIN(2), SUPER_ADMIN(3);

	private final int rank;

	StaffRole(int rank) {
		this.rank = rank;
	}

	public void require(StaffRole needed) {
		if (rank < needed.rank) {
			throw new ApiException(403, "insufficient_role");
		}
	}

}
