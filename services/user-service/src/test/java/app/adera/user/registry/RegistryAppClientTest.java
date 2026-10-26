package app.adera.user.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import app.adera.user.admin.ApiException;
import app.adera.user.config.UserProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RegistryAppClientTest {

	private HttpServer server;
	private final AtomicInteger calls = new AtomicInteger();
	private final UUID known = UUID.randomUUID();

	private RegistryAppClient client(String url, Clock clock) {
		UserProperties props = new UserProperties("", "secret", "", new UserProperties.Kratos("", ""), new UserProperties.Registry(url), null, null, 1000);
		return new RegistryAppClient(props, RestClient.builder(), clock);
	}

	private String startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/apps/", ex -> {
			calls.incrementAndGet();
			boolean hit = ex.getRequestURI().getPath().endsWith(known.toString()) && "secret".equals(ex.getRequestHeaders().getFirst("X-Internal-Secret"));
			byte[] body = (hit ? "{\"appId\":\"" + known + "\",\"slug\":\"x\",\"status\":\"LIVE\"}" : "{\"error\":\"not_found\"}").getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(hit ? 200 : 404, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void knownAppsAreRememberedForAMinuteAndUnknownOnesAreNot() throws Exception {
		Instant[] now = { Instant.parse("2026-10-26T10:00:00Z") };
		Clock clock = new Clock() {
			public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
			public Clock withZone(java.time.ZoneId z) { return this; }
			public Instant instant() { return now[0]; }
		};
		RegistryAppClient c = client(startServer(), clock);

		assertThat(c.status(known)).contains("LIVE");
		assertThat(c.status(known)).contains("LIVE");
		assertThat(calls.get()).as("second lookup came from the cache").isEqualTo(1);
		now[0] = now[0].plusSeconds(61);
		assertThat(c.status(known)).contains("LIVE");
		assertThat(calls.get()).isEqualTo(2);

		assertThat(c.status(UUID.randomUUID())).isEmpty();
		int afterFirstMiss = calls.get();
		c.status(UUID.randomUUID());
		assertThat(calls.get()).isEqualTo(afterFirstMiss + 1);
	}

	@Test
	void anUnreachableRegistryIsServiceUnavailableNotAnInternalError() {
		RegistryAppClient c = client("http://127.0.0.1:1", Clock.systemUTC());
		assertThatThrownBy(() -> c.status(UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status()).isEqualTo(503);
			assertThat(e.code()).isEqualTo("service_unavailable");
		});
	}

}
