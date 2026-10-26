package app.adera.notification.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** Prototype-scoped by design: each provider customises its own copy (base URL), so they never share one. */
	@Bean
	@org.springframework.context.annotation.Scope("prototype")
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

}
