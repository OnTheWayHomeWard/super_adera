package app.adera.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.adera.notification.dispatch.SendLog;
import app.adera.notification.dispatch.SendLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Whole service against a real Postgres (Testcontainers): Flyway migration, the HTTP contract Kratos uses,
 * the send log, per-recipient rate limiting and the dev message endpoint, all with the dev providers.
 */
@SpringBootTest(properties = {
		"adera.notification.sms-provider=console",
		"adera.notification.email-provider=mailpit",
		"adera.notification.dev-endpoints=true",
		"adera.notification.limits.sms-per-hour=3",
		"spring.mail.host=localhost",
		"spring.mail.port=1" // nothing listens here: email tests below expect a clean 502, not a hang
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationServiceApplicationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	SendLogRepository logs;

	@BeforeEach
	void reset() throws Exception {
		logs.deleteAll();
		mvc.perform(delete("/dev/messages")).andExpect(status().isOk());
	}

	private static String sms(String to) {
		return """
				{"to":"%s","body":"Your login code is: 123456","templateType":"login_code_valid"}""".formatted(to);
	}

	@Test
	void smsIsDeliveredLoggedAndVisibleToDevTools() throws Exception {
		mvc.perform(post("/internal/kratos/sms").contentType(MediaType.APPLICATION_JSON).content(sms("+251911234567")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("sent"))
			.andExpect(jsonPath("$.provider").value("console"));

		mvc.perform(get("/dev/messages").param("to", "+251911234567"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].body").value("Your login code is: 123456"));

		var rows = logs.findAll();
		assertThat(rows).hasSize(1);
		SendLog row = rows.get(0);
		assertThat(row.getStatus()).isEqualTo(SendLog.Status.SENT);
		assertThat(row.getRecipientMasked()).isEqualTo("+2519******67").doesNotContain("1234");
		assertThat(row.getRecipientHash()).hasSize(64);
		assertThat(row.getTemplateType()).isEqualTo("login_code_valid");
	}

	@Test
	void sendLogNeverContainsTheCodeOrTheFullNumber() throws Exception {
		mvc.perform(post("/internal/kratos/sms").contentType(MediaType.APPLICATION_JSON).content(sms("+251911234567")))
			.andExpect(status().isOk());
		String everything = logs.findAll().stream()
			.map(r -> String.join("|", r.getRecipientMasked(), r.getRecipientHash(), String.valueOf(r.getTemplateType()),
					r.getProvider(), String.valueOf(r.getErrorCode())))
			.reduce("", String::concat);
		assertThat(everything).doesNotContain("123456").doesNotContain("+251911234567");
	}

	@Test
	void fourthSmsToTheSameNumberIsSuppressedButStillAnswers200() throws Exception {
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/internal/kratos/sms").contentType(MediaType.APPLICATION_JSON).content(sms("+251922000000")))
				.andExpect(jsonPath("$.status").value("sent"));
		}
		// 200 (not 429) on purpose: Kratos retries non-2xx, which would defeat the limit.
		mvc.perform(post("/internal/kratos/sms").contentType(MediaType.APPLICATION_JSON).content(sms("+251922000000")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("suppressed"));
		// and a different number is unaffected
		mvc.perform(post("/internal/kratos/sms").contentType(MediaType.APPLICATION_JSON).content(sms("+251933000000")))
			.andExpect(jsonPath("$.status").value("sent"));
		assertThat(logs.findAll().stream().filter(r -> r.getStatus() == SendLog.Status.SUPPRESSED)).hasSize(1);
	}

	@Test
	void invalidRequestIsRejected() throws Exception {
		mvc.perform(post("/internal/kratos/sms").contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"\",\"body\":\"x\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void emailDeliveryFailureIsA502SoKratosRetriesAndIsLogged() throws Exception {
		mvc.perform(post("/internal/kratos/email").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"recipient":"a@example.com","subject":"Use code 1","body":"Your code is 1","templateType":"login_code_valid"}"""))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error").value("delivery_failed"));
		assertThat(logs.findAll()).singleElement().satisfies(r -> {
			assertThat(r.getStatus()).isEqualTo(SendLog.Status.FAILED);
			assertThat(r.getRecipientMasked()).isEqualTo("a***@example.com");
		});
	}

	@Test
	void healthEndpointIsUp() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

}
