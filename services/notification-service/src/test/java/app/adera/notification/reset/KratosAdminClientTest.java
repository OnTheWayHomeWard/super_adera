package app.adera.notification.reset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KratosAdminClientTest {

	@Test
	void aPlusInAPhoneNumberIsSentAsPercent2BNotAsASpace() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KratosAdminClient client = KratosAdminClient.forBuilder("http://kratos:4434", builder);
		UUID id = UUID.randomUUID();
		server.expect(requestTo("http://kratos:4434/admin/identities?credentials_identifier=%2B251911000001")).andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("[{\"id\":\"" + id + "\",\"schema_id\":\"user\",\"state\":\"active\"}]", MediaType.APPLICATION_JSON));
		assertThat(client.findBySignInIdentifier("+251911000001")).contains(new KratosAdminClient.Identity(id, "user", "active"));
		server.verify();
	}

	@Test
	void noIdentityMeansEmpty() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KratosAdminClient client = KratosAdminClient.forBuilder("http://kratos:4434", builder);
		server.expect(requestTo("http://kratos:4434/admin/identities?credentials_identifier=%2B251911999999")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
		assertThat(client.findBySignInIdentifier("+251911999999")).isEmpty();
	}

	@Test
	void resetPutsTheOldTraitsBackWithTheNewPasswordThenSignsTheAccountOut() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KratosAdminClient client = KratosAdminClient.forBuilder("http://kratos:4434", builder);
		UUID id = UUID.randomUUID();
		server.expect(requestTo("http://kratos:4434/admin/identities/" + id)).andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"id\":\"" + id + "\",\"schema_id\":\"user\",\"state\":\"active\",\"traits\":{\"phone_number\":\"+251911000001\"},\"metadata_public\":null}", MediaType.APPLICATION_JSON));
		server.expect(requestTo("http://kratos:4434/admin/identities/" + id)).andExpect(method(HttpMethod.PUT))
			.andExpect(content().string(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("\"password\":\"abcdef\""), org.hamcrest.Matchers.containsString("+251911000001"), org.hamcrest.Matchers.containsString("\"schema_id\":\"user\""))))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		server.expect(requestTo("http://kratos:4434/admin/identities/" + id + "/sessions")).andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess());
		client.resetPassword(id, "abcdef");
		server.verify();
	}

}
