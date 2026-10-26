package app.adera.notification.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import app.adera.notification.TestcontainersConfiguration;
import app.adera.notification.dispatch.Masking;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The pre-flight check for sign-in codes: it counts requests for ANY address, so it can say "too many, wait N seconds"
 * truthfully without revealing which accounts exist.
 */
@SpringBootTest(properties = { "adera.partner.workers-enabled=false", "adera.partner.gateway-secret=gw-secret",
		"adera.notification.dev-endpoints=true", "adera.notification.limits.email-per-hour=3", "adera.notification.limits.email-per-day=5",
		"adera.notification.limits.sms-per-hour=2", "adera.notification.limits.sms-per-day=4" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginCodeGateTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	private ResultActions check(String identifier) throws Exception {
		return mvc.perform(post("/public/v1/login-codes/check").contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"" + identifier + "\"}"));
	}

	private static String email() {
		return "gate-" + UUID.randomUUID().toString().substring(0, 8) + "@Example.com";
	}

	@Test
	void eachRequestUsesOneOfTheHourlyAllowanceThenTheAnswerIs429WithTheTimeToWait() throws Exception {
		String who = email();
		check(who).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.limitPerHour").value(3)).andExpect(jsonPath("$.remainingThisHour").value(2));
		check(who).andExpect(status().isOk()).andExpect(jsonPath("$.remainingThisHour").value(1));
		// the address is case-insensitive, like the real limit
		check(who.toLowerCase()).andExpect(status().isOk()).andExpect(jsonPath("$.remainingThisHour").value(0));
		check(who).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("too_many_codes")).andExpect(jsonPath("$.allowed").value(false))
			.andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.greaterThan(3500), org.hamcrest.Matchers.lessThanOrEqualTo(3601))))
			.andExpect(header().exists("Retry-After"));
		// somebody else is not affected
		check(email()).andExpect(status().isOk()).andExpect(jsonPath("$.remainingThisHour").value(2));
	}

	@Test
	void theWaitShrinksAsOldRequestsLeaveTheWindowAndTheAllowanceComesBack() throws Exception {
		String who = email();
		for (int i = 0; i < 3; i++) {
			check(who).andExpect(status().isOk());
		}
		String hash = Masking.hash(who);
		// the oldest two requests were 59 minutes ago: the third of the three must leave the window... the OLDEST one frees a slot first
		jdbc.sql("UPDATE code_request SET created_at = created_at - interval '59 minutes' WHERE recipient_hash = :h").param("h", hash).update();
		check(who).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.greaterThan(0), org.hamcrest.Matchers.lessThanOrEqualTo(65))));
		jdbc.sql("UPDATE code_request SET created_at = created_at - interval '2 minutes' WHERE recipient_hash = :h").param("h", hash).update();
		check(who).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
	}

	@Test
	void theDailyLimitAppliesEvenWhenTheHourlyOneIsFree() throws Exception {
		String who = email();
		String hash = Masking.hash(who);
		for (int i = 0; i < 5; i++) {
			jdbc.sql("INSERT INTO code_request (id, channel, recipient_hash, created_at) VALUES (:id, 'EMAIL', :h, now() - interval '3 hours')").param("id", UUID.randomUUID()).param("h", hash).update();
		}
		check(who).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.limitPerDay").value(5)).andExpect(jsonPath("$.remainingToday").value(0))
			.andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.greaterThan(3600)));
	}

	@Test
	void phonesHaveTheirOwnSmallerLimitAndSeparatorsDoNotMatter() throws Exception {
		String tail = String.valueOf(10_000_000 + new java.util.Random().nextInt(89_999_999));
		check("+2519" + tail).andExpect(status().isOk()).andExpect(jsonPath("$.limitPerHour").value(2)).andExpect(jsonPath("$.remainingThisHour").value(1));
		check("+251 9" + tail.substring(0, 2) + "-" + tail.substring(2)).andExpect(status().isOk()).andExpect(jsonPath("$.remainingThisHour").value(0));
		check("+2519" + tail).andExpect(status().isTooManyRequests());
	}

	@Test
	void anAddressThatNeverHadAnAccountIsTreatedExactlyLikeAnyOther() throws Exception {
		// same numbers, same shape: nothing in the answer depends on whether the address is registered
		String a = check(email()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String b = check(email()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(a).isEqualTo(b);
	}

	@Test
	void notAnEmailOrPhoneIsAValidationError() throws Exception {
		for (String bad : new String[] { "nope", "a@", "@b.c", "12", "+25191", "hello world" }) {
			check(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.identifier").exists());
		}
		mvc.perform(post("/public/v1/login-codes/check").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
	}

}
