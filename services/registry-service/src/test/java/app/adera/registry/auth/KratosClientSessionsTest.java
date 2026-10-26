package app.adera.registry.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.UUID;

import app.adera.registry.config.RegistryProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Revoking a person's Kratos sessions when they never had one is not an error; a Kratos outage still is. */
class KratosClientSessionsTest {

	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	private KratosClient clientAnswering(int status) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
		String url = "http://127.0.0.1:" + server.getAddress().getPort();
		RegistryProperties props = new RegistryProperties("", "", true, "", "dev", new RegistryProperties.Kratos(url, url),
				new RegistryProperties.Storage("", "", "", "", "", "", 60), new RegistryProperties.Logo(1, 1));
		return new KratosClient(props, RestClient.builder());
	}

	@Test
	void identityThatNeverSignedInHasNoSessionsToRevoke() throws Exception {
		KratosClient kratos = clientAnswering(404); // Kratos answers 404 when the identity has no sessions
		assertThatCode(() -> kratos.revokeSessions(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void revokingExistingSessionsWorks() throws Exception {
		KratosClient kratos = clientAnswering(204);
		assertThatCode(() -> kratos.revokeSessions(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void aKratosFailureIsStillReportedAsUnavailable() throws Exception {
		KratosClient kratos = clientAnswering(500);
		assertThatThrownBy(() -> kratos.revokeSessions(UUID.randomUUID())).isInstanceOf(KratosClient.KratosUnavailableException.class);
	}

}
