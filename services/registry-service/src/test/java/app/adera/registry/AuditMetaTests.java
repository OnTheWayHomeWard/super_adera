package app.adera.registry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Audit meta must be real JSON (fields, nulls), never the text rendering of a Java object. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditMetaTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@MockitoBean
	KratosClient kratos;

	private static final UUID STAFF_ID = UUID.randomUUID();

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", STAFF_ID.toString()).header("X-Staff-Role", role.name());
	}

	private UUID createApp() throws Exception {
		String slug = "m-" + UUID.randomUUID().toString().substring(0, 8);
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("slug", slug, "name", Map.of("en", "App " + slug)))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	@Test
	void limitSetStoresTheChannelAndEveryLimitAsStructuredMetaWithNullForUnlimited() throws Exception {
		UUID app = createApp();
		mvc.perform(as(put("/admin/api/v1/apps/{id}/notification-limits/sms", app), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"maxPerUserPerDay\":2,\"maxPerDay\":50,\"maxTotal\":1000}")).andExpect(status().isOk());
		mvc.perform(as(get("/admin/api/v1/audit").param("action", "limit.set").param("appId", app.toString()), Role.ADMIN)).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].meta.channel").value("SMS"))
			.andExpect(jsonPath("$[0].meta.maxPerUserPerDay").value(2))
			.andExpect(jsonPath("$[0].meta.maxPerDay").value(50))
			.andExpect(jsonPath("$[0].meta.maxDistinctUsersPerDay").doesNotExist())
			.andExpect(jsonPath("$[0].meta.maxTotal").value(1000))
			.andExpect(jsonPath("$[0].meta.limits").doesNotExist());
		// null really is stored as null (the key is present), meaning unlimited
		String meta = mvc.perform(as(get("/admin/api/v1/audit").param("action", "limit.set").param("appId", app.toString()), Role.ADMIN)).andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(JsonPath.<Map<String, Object>>read(meta, "$[0].meta")).containsKeys("channel", "maxPerUserPerDay", "maxPerDay", "maxDistinctUsersPerDay", "maxTotal")
			.containsEntry("maxDistinctUsersPerDay", null);
	}

	@Test
	void paymentSetStoresTheAmountLimitsToo() throws Exception {
		UUID app = createApp();
		mvc.perform(as(put("/admin/api/v1/apps/{id}/payment-settings", app), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedCurrencies\":[\"ETB\"],\"allowHold\":true,\"minAmountMinor\":100}")).andExpect(status().isOk());
		String meta = mvc.perform(as(get("/admin/api/v1/audit").param("action", "payment.set").param("appId", app.toString()), Role.ADMIN)).andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(JsonPath.<Map<String, Object>>read(meta, "$[0].meta")).containsEntry("allowHold", true).containsEntry("minAmountMinor", 100)
			.containsEntry("maxAmountMinor", null).containsKey("currencies");
	}

}
