package app.adera.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class InfisicalEnvironmentPostProcessorTest {

	/** Real logging is fine in tests: no need to defer. */
	private final org.springframework.boot.logging.DeferredLogFactory logFactory = java.util.function.Supplier::get;

	@Test
	void parsesTheSecretsListFromTheApiResponse() {
		Map<String, Object> out = InfisicalEnvironmentPostProcessor.parse("""
				{"secrets":[
				  {"secretKey":"TWILIO_ACCOUNT_SID","secretValue":"ACabc","version":1},
				  {"secretKey":"RESEND_FROM_NAME","secretValue":"Adera"},
				  {"secretKey":"","secretValue":"ignored"}
				],"imports":[]}""");
		assertThat(out).containsEntry("TWILIO_ACCOUNT_SID", "ACabc").containsEntry("RESEND_FROM_NAME", "Adera").hasSize(2);
	}

	@Test
	void doesNothingWithoutInfisicalSettings() {
		MockEnvironment env = new MockEnvironment();
		new InfisicalEnvironmentPostProcessor(logFactory).postProcessEnvironment(env, new SpringApplication());
		assertThat(env.getPropertySources().contains("infisical")).isFalse();
	}

	@Test
	void anUnreachableInfisicalDoesNotStopStartup() {
		MockEnvironment env = new MockEnvironment()
			.withProperty("INFISICAL_TOKEN", "t")
			.withProperty("INFISICAL_PROJECT_ID", "p")
			.withProperty("INFISICAL_DOMAIN", "http://127.0.0.1:1");
		new InfisicalEnvironmentPostProcessor(logFactory).postProcessEnvironment(env, new SpringApplication());
		assertThat(env.getPropertySources().contains("infisical")).isFalse();
	}

	@Test
	void loadedSecretsHaveLowestPriorityAndAreOverridable() {
		MockEnvironment env = new MockEnvironment().withProperty("TWILIO_ACCOUNT_SID", "from-env");
		env.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource("infisical",
				Map.of("TWILIO_ACCOUNT_SID", "from-infisical", "RESEND_API_KEY", "re_x")));
		assertThat(env.getProperty("TWILIO_ACCOUNT_SID")).isEqualTo("from-env");
		assertThat(env.getProperty("RESEND_API_KEY")).isEqualTo("re_x");
	}

}
