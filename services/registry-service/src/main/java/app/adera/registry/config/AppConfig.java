package app.adera.registry.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

	/** The environment this deployment is (ADERA_ENV); refuses to start on anything but dev or prod. */
	@Bean
	app.adera.registry.common.Environment deploymentEnvironment(RegistryProperties props) {
		return app.adera.registry.common.Environment.ofDeployment(props.environment());
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** Prototype on purpose: each client customises its own copy (base URL), so they never share one. */
	@Bean
	@Scope("prototype")
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

}
