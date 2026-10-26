package app.adera.registry.staff;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.registry.audit.Actor;
import app.adera.registry.audit.AuditService;
import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import app.adera.registry.common.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard staff. The role is stored in Kratos (metadata_public.role: only the admin API can write it, a user
 * cannot edit it through any self-service flow) and mirrored here for listing, status and audit.
 */
@Service
public class StaffService {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");

	private final StaffRepository staff;
	private final KratosClient kratos;
	private final AuditService audit;
	private final Clock clock;

	public StaffService(StaffRepository staff, KratosClient kratos, AuditService audit, Clock clock) {
		this.staff = staff;
		this.kratos = kratos;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Staff> list() {
		return staff.findAllByOrderByCreatedAtAsc();
	}

	/**
	 * Makes this email a staff member with this role: creates the Kratos identity if the person has never signed in,
	 * otherwise just grants the role on the existing identity. Idempotent.
	 */
	@Transactional
	public Staff ensure(String rawEmail, Role role, Actor actor) {
		String email = normalise(rawEmail);
		Instant now = clock.instant();
		KratosClient.IdentityRef identity = kratos.findByEmail(email).orElse(null);
		Staff existing = identity == null ? null : staff.findById(identity.id()).orElse(null);
		if (existing != null) {
			// "adding" someone who is already staff is a role change: it must obey the same rules as PATCH /staff/{id}
			guardSuperAdminLoss(existing, role, Staff.Status.ACTIVE, actor);
		}
		if (identity == null) {
			identity = kratos.createStaffIdentity(email, role);
		}
		else {
			kratos.setRole(identity.id(), role);
		}
		Staff member = staff.findById(identity.id()).orElse(null);
		if (member == null) {
			member = staff.save(new Staff(identity.id(), email, role, actor == null ? null : actor.id(), now));
			audit.record(actor, "staff.create", "staff", identity.id().toString(), Map.of("email", email, "role", role.name()));
		}
		else {
			member.setRole(role, now);
			member.setStatus(Staff.Status.ACTIVE, now);
			audit.record(actor, "staff.update", "staff", identity.id().toString(), Map.of("role", role.name(), "status", "ACTIVE"));
		}
		return member;
	}

	@Transactional
	public Staff update(UUID identityId, Map<String, Object> body, Actor actor) {
		Staff member = staff.findById(identityId).orElseThrow(() -> new Errors.NotFound("staff member"));
		Role newRole = member.getRole();
		Staff.Status newStatus = member.getStatus();
		Map<String, String> errors = new LinkedHashMap<>();
		for (String key : body.keySet()) {
			if (!key.equals("role") && !key.equals("status")) {
				errors.put(key, "unknown field");
			}
		}
		if (body.containsKey("role")) {
			newRole = Role.parseOrNull(String.valueOf(body.get("role")));
			if (newRole == null) {
				errors.put("role", "must be one of VIEWER, ADMIN, SUPER_ADMIN");
			}
		}
		if (body.containsKey("status")) {
			try {
				newStatus = Staff.Status.valueOf(String.valueOf(body.get("status")).toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException e) {
				errors.put("status", "must be ACTIVE or DISABLED");
			}
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		guardSuperAdminLoss(member, newRole, newStatus, actor);
		Instant now = clock.instant();
		if (newStatus == Staff.Status.DISABLED) {
			kratos.setRole(identityId, null);
			kratos.revokeSessions(identityId);
		}
		else {
			kratos.setRole(identityId, newRole);
		}
		member.setRole(newRole, now);
		member.setStatus(newStatus, now);
		audit.record(actor, "staff.update", "staff", identityId.toString(), Map.of("role", newRole.name(), "status", newStatus.name()));
		return member;
	}

	/** Nobody may demote or disable themselves, and the last active SUPER_ADMIN may never be demoted or disabled. */
	private void guardSuperAdminLoss(Staff member, Role newRole, Staff.Status newStatus, Actor actor) {
		boolean losesSuperAdmin = member.getRole() == Role.SUPER_ADMIN && member.getStatus() == Staff.Status.ACTIVE
				&& (newRole != Role.SUPER_ADMIN || newStatus != Staff.Status.ACTIVE);
		if (losesSuperAdmin && (actor != null && member.getIdentityId().equals(actor.id()))) {
			throw new Errors.Conflict("cannot_demote_self");
		}
		if (losesSuperAdmin && staff.countByRoleAndStatus(Role.SUPER_ADMIN, Staff.Status.ACTIVE) <= 1) {
			throw new Errors.Conflict("last_super_admin");
		}
	}

	static String normalise(String email) {
		String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		if (!EMAIL.matcher(e).matches()) {
			throw new Errors.ValidationFailed("email", "must be a valid email address");
		}
		return e;
	}

}
