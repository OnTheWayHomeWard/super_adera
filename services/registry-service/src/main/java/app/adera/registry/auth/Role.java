package app.adera.registry.auth;

import app.adera.registry.common.Errors;

/** Dashboard roles. Deliberately few: higher rank includes everything below it. */
public enum Role {

	VIEWER(1), ADMIN(2), SUPER_ADMIN(3);

	private final int rank;

	Role(int rank) {
		this.rank = rank;
	}

	public boolean atLeast(Role needed) {
		return rank >= needed.rank;
	}

	public void require(Role needed) {
		if (!atLeast(needed)) {
			throw new Errors.Forbidden("insufficient_role");
		}
	}

	public static Role parseOrNull(String value) {
		try {
			return value == null ? null : valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
