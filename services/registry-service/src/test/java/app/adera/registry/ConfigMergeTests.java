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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** PUT notification-limits and PUT payment-settings MERGE: an omitted field keeps its value, an explicit null clears it. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConfigMergeTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@MockitoBean
	KratosClient kratos;

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", role.name());
	}

	private UUID createApp() throws Exception {
		String slug = "c-" + UUID.randomUUID().toString().substring(0, 8);
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("slug", slug, "name", Map.of("en", "App " + slug))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	private ResultActions putLimit(UUID app, String channel, String body) throws Exception {
		return mvc.perform(as(put("/admin/api/v1/apps/{id}/notification-limits/{c}", app, channel), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions putPayment(UUID app, String body) throws Exception {
		return mvc.perform(as(put("/admin/api/v1/apps/{id}/payment-settings", app), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void aLimitFieldYouOmitKeepsItsValueAndNullMeansUnlimited() throws Exception {
		UUID app = createApp();
		putLimit(app, "SMS", "{\"maxPerUserPerDay\":2,\"maxPerDay\":50,\"maxDistinctUsersPerDay\":25,\"maxTotal\":500}").andExpect(status().isOk());

		// one field: the other three stay
		putLimit(app, "SMS", "{\"maxPerDay\":60}").andExpect(status().isOk())
			.andExpect(jsonPath("$.maxPerUserPerDay").value(2)).andExpect(jsonPath("$.maxPerDay").value(60))
			.andExpect(jsonPath("$.maxDistinctUsersPerDay").value(25)).andExpect(jsonPath("$.maxTotal").value(500));
		// explicit null = unlimited, the others stay
		putLimit(app, "SMS", "{\"maxTotal\":null}").andExpect(status().isOk())
			.andExpect(jsonPath("$.maxTotal").doesNotExist()).andExpect(jsonPath("$.maxPerDay").value(60)).andExpect(jsonPath("$.maxPerUserPerDay").value(2));
		// 0 still means "none"
		putLimit(app, "SMS", "{\"maxPerUserPerDay\":0}").andExpect(jsonPath("$.maxPerUserPerDay").value(0)).andExpect(jsonPath("$.maxPerDay").value(60));
		// an empty body changes nothing
		putLimit(app, "SMS", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.maxPerDay").value(60)).andExpect(jsonPath("$.maxDistinctUsersPerDay").value(25));
		// the stored values are what the app detail shows
		mvc.perform(as(get("/admin/api/v1/apps/{id}", app), Role.VIEWER)).andExpect(jsonPath("$.notificationLimits[?(@.channel=='SMS')].maxPerDay").value(60))
			.andExpect(jsonPath("$.notificationLimits[?(@.channel=='SMS')].maxPerUserPerDay").value(0));
		// a bad value is still refused and changes nothing
		putLimit(app, "SMS", "{\"maxPerDay\":-5}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.maxPerDay").exists());
		mvc.perform(as(get("/admin/api/v1/apps/{id}", app), Role.VIEWER)).andExpect(jsonPath("$.notificationLimits[?(@.channel=='SMS')].maxPerDay").value(60));
	}

	@Test
	void aChannelWithoutLimitsYetStartsFromTheDefaultsForOmittedFields() throws Exception {
		UUID app = createApp();
		putLimit(app, "EMAIL", "{\"maxPerDay\":7}").andExpect(status().isOk())
			.andExpect(jsonPath("$.maxPerDay").value(7)).andExpect(jsonPath("$.maxPerUserPerDay").value(5)).andExpect(jsonPath("$.maxDistinctUsersPerDay").value(500))
			.andExpect(jsonPath("$.maxTotal").value(10000));
		putLimit(app, "IN_APP", "{\"maxTotal\":null}").andExpect(jsonPath("$.maxPerUserPerDay").value(20)).andExpect(jsonPath("$.maxPerDay").value(10000));
	}

	@Test
	void theAuditEntryRecordsTheMergedValues() throws Exception {
		UUID app = createApp();
		putLimit(app, "SMS", "{\"maxPerUserPerDay\":2,\"maxPerDay\":50,\"maxDistinctUsersPerDay\":25,\"maxTotal\":500}").andExpect(status().isOk());
		putLimit(app, "SMS", "{\"maxPerDay\":60}").andExpect(status().isOk());
		mvc.perform(as(get("/admin/api/v1/audit").param("action", "limit.set").param("appId", app.toString()), Role.ADMIN))
			.andExpect(jsonPath("$[0].meta.maxPerDay").value(60)).andExpect(jsonPath("$[0].meta.maxPerUserPerDay").value(2)).andExpect(jsonPath("$[0].meta.maxTotal").value(500));
	}

	@Test
	void paymentSettingsMergeAndNullClearsEachField() throws Exception {
		UUID app = createApp();
		// first time: omitted fields take the defaults (no currency, no holds, no amount limits)
		putPayment(app, "{\"allowHold\":true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.allowHold").value(true)).andExpect(jsonPath("$.allowedCurrencies.length()").value(0)).andExpect(jsonPath("$.minAmountMinor").doesNotExist());
		putPayment(app, "{\"allowedCurrencies\":[\"etb\",\"USD\"],\"minAmountMinor\":100,\"maxAmountMinor\":900}").andExpect(status().isOk())
			.andExpect(jsonPath("$.allowHold").value(true)).andExpect(jsonPath("$.allowedCurrencies.length()").value(2));

		// only the maximum is cleared; the rest stays
		putPayment(app, "{\"maxAmountMinor\":null}").andExpect(status().isOk())
			.andExpect(jsonPath("$.maxAmountMinor").doesNotExist()).andExpect(jsonPath("$.minAmountMinor").value(100)).andExpect(jsonPath("$.allowedCurrencies.length()").value(2))
			.andExpect(jsonPath("$.allowHold").value(true));
		// omitted fields stay, including the currencies
		putPayment(app, "{\"minAmountMinor\":150}").andExpect(jsonPath("$.minAmountMinor").value(150)).andExpect(jsonPath("$.allowedCurrencies.length()").value(2));
		// null per field: currencies -> none, hold -> off
		putPayment(app, "{\"allowedCurrencies\":null,\"allowHold\":null}").andExpect(status().isOk())
			.andExpect(jsonPath("$.allowedCurrencies.length()").value(0)).andExpect(jsonPath("$.allowHold").value(false)).andExpect(jsonPath("$.minAmountMinor").value(150));
		// the merged values must be consistent: a minimum above the stored maximum is refused
		putPayment(app, "{\"maxAmountMinor\":200}").andExpect(status().isOk());
		putPayment(app, "{\"minAmountMinor\":250}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.minAmountMinor").exists());
		putPayment(app, "{\"allowHold\":\"yes\",\"allowedCurrencies\":[\"EUR\"],\"tip\":1}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.allowHold").exists()).andExpect(jsonPath("$.fields.allowedCurrencies").exists()).andExpect(jsonPath("$.fields.tip").exists());
		mvc.perform(as(get("/admin/api/v1/apps/{id}", app), Role.VIEWER)).andExpect(jsonPath("$.payment.maxAmountMinor").value(200)).andExpect(jsonPath("$.payment.minAmountMinor").value(150));
	}

}
