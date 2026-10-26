package app.adera.notification.reset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.adera.notification.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Password reset by phone: one code by SMS, five tries, once, the same answer for every phone, staff refused. */
@SpringBootTest(properties = { "adera.partner.workers-enabled=false", "adera.partner.gateway-secret=gw-secret",
		"adera.notification.dev-endpoints=true", "adera.notification.limits.sms-per-hour=3", "adera.notification.limits.sms-per-day=6" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PasswordResetTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@MockitoBean KratosAdminClient kratos;

	private static String phone() {
		return "+2519" + (10_000_000 + new java.util.Random().nextInt(89_999_999));
	}

	private ResultActions start(String identifier) throws Exception {
		return mvc.perform(post("/public/v1/password-reset/start").contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"" + identifier + "\"}"));
	}

	private ResultActions finish(String identifier, String code, String password) throws Exception {
		return mvc.perform(post("/public/v1/password-reset/finish").contentType(MediaType.APPLICATION_JSON)
			.content("{\"identifier\":\"" + identifier + "\",\"code\":\"" + code + "\",\"password\":" + (password == null ? "null" : "\"" + password + "\"") + "}"));
	}

	private String sentCode(String phone) throws Exception {
		String body = mvc.perform(get("/dev/messages").param("to", phone)).andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("password reset code is (\\d{6})").matcher(body);
		assertThat(m.find()).as("an SMS with the code was sent to " + phone).isTrue();
		return m.group(1);
	}

	private UUID known(String phone, String schema, String state) {
		UUID id = UUID.randomUUID();
		when(kratos.findBySignInIdentifier(phone)).thenReturn(Optional.of(new KratosAdminClient.Identity(id, schema, state)));
		return id;
	}

	@Test
	void aKnownPhoneGetsACodeAndTheRightCodeChangesThePasswordOnce() throws Exception {
		String p = phone();
		UUID id = known(p, "user", "active");
		start(p).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("sent")).andExpect(jsonPath("$.expiresInSeconds").value(600));
		String code = sentCode(p);
		finish(p, code, "new-secret").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("password_changed"));
		verify(kratos).resetPassword(id, "new-secret");
		// the code worked once
		finish(p, code, "other-pass").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_code"));
		verify(kratos, never()).resetPassword(id, "other-pass");
	}

	@Test
	void anUnknownPhoneGetsTheSameAnswerAndNoSms() throws Exception {
		String known = phone(), unknown = phone();
		known(known, "user", "active");
		when(kratos.findBySignInIdentifier(unknown)).thenReturn(Optional.empty());
		String a = start(known).andReturn().getResponse().getContentAsString();
		String b = start(unknown).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		assertThat(a).isEqualTo(b);
		assertThat(mvc.perform(get("/dev/messages").param("to", unknown)).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
		finish(unknown, "123456", "new-secret").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_code"));
	}

	@Test
	void fiveWrongTriesBurnTheCodeEvenIfTheNextOneIsRight() throws Exception {
		String p = phone();
		known(p, "user", "active");
		start(p).andExpect(status().isAccepted());
		String code = sentCode(p);
		String wrong = code.equals("000000") ? "000001" : "000000";
		for (int i = 0; i < 5; i++) {
			finish(p, wrong, "new-secret").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_code"));
		}
		finish(p, code, "new-secret").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_code"));
		verify(kratos, never()).resetPassword(any(), eq("new-secret"));
	}

	@Test
	void anExpiredCodeIsRefused() throws Exception {
		String p = phone();
		known(p, "user", "active");
		start(p).andExpect(status().isAccepted());
		String code = sentCode(p);
		jdbc.sql("UPDATE password_reset SET expires_at = now() - interval '1 minute' WHERE identity_id IS NOT NULL AND created_at > now() - interval '1 minute' AND attempts = 0 AND recipient_hash = :h")
			.param("h", app.adera.notification.dispatch.Masking.hash(p)).update();
		finish(p, code, "new-secret").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_code"));
	}

	@Test
	void aShortPasswordIsRejectedBeforeTheCodeIsTouched() throws Exception {
		String p = phone();
		known(p, "user", "active");
		start(p).andExpect(status().isAccepted());
		String code = sentCode(p);
		finish(p, code, "12345").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.password").exists());
		finish(p, code, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
		finish(p, code, "123456").andExpect(status().isOk());   // the code was not used up by the rejected attempts
	}

	@Test
	void staffAccountsAndSuspendedAccountsGetNothing() throws Exception {
		String staff = phone(), blocked = phone();
		known(staff, "default", "active");
		known(blocked, "user", "inactive");
		start(staff).andExpect(status().isAccepted());
		start(blocked).andExpect(status().isAccepted());
		assertThat(mvc.perform(get("/dev/messages").param("to", staff)).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
		assertThat(mvc.perform(get("/dev/messages").param("to", blocked)).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
	}

	@Test
	void anEmailAddressOrGarbageIsAValidationError() throws Exception {
		for (String bad : new String[] { "someone@example.com", "nope", "+25191" }) {
			start(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.identifier").exists());
		}
	}

	@Test
	void thePerPhoneLimitSharedWithTheCodeCheckAnswers429() throws Exception {
		String p = phone();
		known(p, "user", "active");
		for (int i = 0; i < 3; i++) {
			start(p).andExpect(status().isAccepted());
		}
		start(p).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_codes")).andExpect(jsonPath("$.retryAfterSeconds").exists());
	}

}
