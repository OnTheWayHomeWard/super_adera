package app.adera.user.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

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
