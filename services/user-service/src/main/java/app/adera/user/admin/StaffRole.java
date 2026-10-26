package app.adera.user.admin;

/** The dashboard roles the gateway forwards in X-Staff-Role. Higher rank includes everything below it. */
public enum StaffRole {

	VIEWER(1), ADMIN(2), SUPER_ADMIN(3);

	private final int rank;

	StaffRole(int rank) {
		this.rank = rank;
	}

	public boolean atLeast(StaffRole needed) {
		return rank >= needed.rank;
	}

	public void require(StaffRole needed) {
		if (!atLeast(needed)) {
			throw new ApiException(403, "insufficient_role");
		}
	}

}
