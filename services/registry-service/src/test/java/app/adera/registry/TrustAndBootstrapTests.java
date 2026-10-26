package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import app.adera.registry.staff.Staff;
import app.adera.registry.staff.StaffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With secrets configured: only the gateway can reach the admin and user APIs, only other services can reach /internal,
 * and a fresh install creates its first super admin from the environment.
 */
@SpringBootTest(properties = { "adera.registry.storage.type=memory", "adera.registry.gateway-secret=gw-secret",
		"adera.registry.internal-secret=int-secret", "adera.registry.bootstrap-super-admin-email=Boot.Admin@Example.com" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TrustAndBootstrapTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	StaffRepository staff;

	@MockitoBean
	KratosClient kratos;

	private static final String STAFF = "X-Staff-Id";

	@Test
	void adminApiNeedsTheGatewaySecret() throws Exception {
		UUID id = UUID.randomUUID();
		mvc.perform(get("/admin/api/v1/apps").header(STAFF, id.toString()).header("X-Staff-Role", "SUPER_ADMIN")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/api/v1/apps").header(STAFF, id.toString()).header("X-Staff-Role", "SUPER_ADMIN").header("X-Gateway-Secret", "wrong"))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/api/v1/apps").header(STAFF, id.toString()).header("X-Staff-Role", "SUPER_ADMIN").header("X-Gateway-Secret", "gw-secret"))
			.andExpect(status().isOk());
		// secret alone is not enough: the staff headers must be well formed
		mvc.perform(get("/admin/api/v1/apps").header("X-Gateway-Secret", "gw-secret")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/api/v1/apps").header(STAFF, id.toString()).header("X-Staff-Role", "GOD").header("X-Gateway-Secret", "gw-secret"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void userApiNeedsTheGatewaySecretToo() throws Exception {
		UUID user = UUID.randomUUID();
		mvc.perform(get("/api/v1/apps").header("X-User-Id", user.toString())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/apps").header("X-User-Id", user.toString()).header("X-Gateway-Secret", "gw-secret")).andExpect(status().isOk());
	}

	@Test
	void internalApiNeedsTheInternalSecretAndTheGatewaySecretDoesNotSubstitute() throws Exception {
		mvc.perform(get("/internal/partner-keys/ak_test_x")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/partner-keys/ak_test_x").header("X-Gateway-Secret", "gw-secret")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/partner-keys/ak_test_x").header("X-Internal-Secret", "int-secret")).andExpect(status().isNotFound());
	}

	@Test
	void internalAuditIsForOtherServicesOnlyAndStaffListMarksTheBootstrapAccount() throws Exception {
		String body = "{\"action\":\"user.status\",\"targetType\":\"app_user\",\"targetId\":\"u1\",\"meta\":{\"to\":\"SUSPENDED\"}}";
		mvc.perform(post("/internal/audit").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/audit").header("X-Gateway-Secret", "gw-secret").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/audit").header("X-Internal-Secret", "int-secret").contentType("application/json").content(body))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.id").exists());
		// the bootstrap account is flagged (and has no creator)
		UUID viewer = UUID.randomUUID();
		long deadline = System.currentTimeMillis() + 15_000;
		while (staff.findByEmailIgnoreCase("boot.admin@example.com").isEmpty() && System.currentTimeMillis() < deadline) {
			Thread.sleep(200);
		}
		mvc.perform(get("/admin/api/v1/staff").header(STAFF, viewer.toString()).header("X-Staff-Role", "SUPER_ADMIN").header("X-Gateway-Secret", "gw-secret"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.email=='boot.admin@example.com')].bootstrap").value(true))
			.andExpect(jsonPath("$[?(@.email=='boot.admin@example.com')].createdBy").value((Object) null));
	}

	@Test
	void staffVerifyIsOnlyForTheGateway() throws Exception {
		when(kratos.whoami("Bearer x")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.INVALID, null));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer x")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer x").header("X-Gateway-Secret", "gw-secret")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer x").header("X-Internal-Secret", "int-secret")).andExpect(status().isUnauthorized());
	}

	@Test
	void healthStaysOpenForProbes() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void theFirstSuperAdminIsCreatedFromTheEnvironmentWhenNoneExists() throws Exception {
		// the bootstrap thread started with the context; it created the Kratos identity through the (mocked) admin API
		long deadline = System.currentTimeMillis() + 15_000;
		while (staff.countByRoleAndStatus(Role.SUPER_ADMIN, Staff.Status.ACTIVE) == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(200);
		}
		assertThat(staff.countByRoleAndStatus(Role.SUPER_ADMIN, Staff.Status.ACTIVE)).isEqualTo(1);
		assertThat(staff.findByEmailIgnoreCase("boot.admin@example.com")).isPresent();
	}

	// the mocked Kratos must answer the bootstrap before the test body runs
	@org.junit.jupiter.api.BeforeEach
	void stubKratos() {
		UUID id = UUID.fromString("00000000-0000-0000-0000-00000000b007");
		when(kratos.findByEmail("boot.admin@example.com")).thenReturn(Optional.empty());
		when(kratos.createStaffIdentity("boot.admin@example.com", Role.SUPER_ADMIN))
			.thenReturn(new KratosClient.IdentityRef(id, "boot.admin@example.com", "SUPER_ADMIN"));
	}

}
