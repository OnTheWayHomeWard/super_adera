package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import app.adera.notification.partner.delivery.PushPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PushPayloadTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Test
	void nestedObjectsAndArraysBecomeJsonStringsNotJavaToString() throws Exception {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("screen", "ride");
		data.put("count", 5);
		data.put("urgent", true);
		data.put("driver", Map.of("name", "Abebe", "rating", 4.5));
		data.put("stops", java.util.List.of("A", Map.of("b", 1)));
		data.put("nothing", null);
		UUID id = UUID.randomUUID();
		UUID app = UUID.randomUUID();

		Map<String, String> out = PushPayload.build(data, id, app, MAPPER);

		assertThat(out).containsEntry("screen", "ride").containsEntry("count", "5").containsEntry("urgent", "true");
		assertThat(MAPPER.readTree(out.get("driver")).get("name").asString()).isEqualTo("Abebe");
		assertThat(out.get("driver")).doesNotContain("=").startsWith("{\"");
		assertThat(MAPPER.readTree(out.get("stops")).get(1).get("b").asInt()).isEqualTo(1);
		assertThat(out).as("null values are left out, FCM refuses them").doesNotContainKey("nothing");
		assertThat(out).containsEntry("notificationId", id.toString()).containsEntry("appId", app.toString());
	}

	@Test
	void theTwoServerKeysAndFcmReservedNamesAreRefused() {
		assertThat(PushPayload.reservedKeyProblem("notificationId")).contains("added by Adera");
		assertThat(PushPayload.reservedKeyProblem("appId")).contains("added by Adera");
		for (String reserved : new String[] { "from", "message_type", "google.sent_time", "GCM.x", "googleThing" }) {
			assertThat(PushPayload.reservedKeyProblem(reserved)).as(reserved).contains("reserved by Firebase");
		}
		for (String fine : new String[] { "screen", "orderId", "appid", "notification", "fromUser" }) {
			assertThat(PushPayload.reservedKeyProblem(fine)).as(fine).isNull();
		}
	}

}
