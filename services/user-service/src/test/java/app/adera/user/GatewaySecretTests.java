package app.adera.user;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import app.adera.user.auth.KratosClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With a gateway secret configured, only the gateway can reach /api and /internal/auth: a request from any other
 * container that merely sets X-User-Id is rejected, so users cannot be spoofed from inside the network.
 */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=test-alias-secret", "adera.user.gateway-secret=s3cret-value" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GatewaySecretTests {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	KratosClient kratos;

	private final UUID id = UUID.randomUUID();

	@Test
	void apiWithoutTheSecretIsRejectedEvenWithAValidUserId() throws Exception {
		mvc.perform(get("/api/v1/me").header("X-User-Id", id.toString())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").header("X-User-Id", id.toString()).header("X-Gateway-Secret", "wrong"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void apiWithTheSecretIsAllowedThrough() throws Exception {
		when(kratos.identity(id)).thenReturn(Optional.of(new KratosClient.Identity(id, "gw-" + id + "@example.com", null)));
		mvc.perform(get("/api/v1/me").header("X-User-Id", id.toString()).header("X-Gateway-Secret", "s3cret-value"))
			.andExpect(status().isOk());
	}

	@Test
	void verifyEndpointAlsoRequiresTheSecret() throws Exception {
		when(kratos.whoami(org.mockito.ArgumentMatchers.eq("Bearer good"), org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.of(id));
		mvc.perform(get("/internal/auth/verify").header("Authorization", "Bearer good")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/auth/verify").header("Authorization", "Bearer good").header("X-Gateway-Secret", "s3cret-value"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-User-Id", id.toString()));
	}

	@Test
	void healthStaysOpenForProbes() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

}
