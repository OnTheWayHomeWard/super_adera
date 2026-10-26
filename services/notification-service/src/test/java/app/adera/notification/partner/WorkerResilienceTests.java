package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import app.adera.notification.TestcontainersConfiguration;
import app.adera.notification.partner.auth.UserDirectoryClient;
import app.adera.notification.partner.delivery.CallbackSender;
import app.adera.notification.partner.delivery.CallbackWorker;
import app.adera.notification.partner.delivery.DeliveryWorker;
import app.adera.notification.partner.delivery.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The workers never loop: a failing, crashing or poisonous item waits, is counted, and is given up on. */
@SpringBootTest(properties = { "adera.partner.workers-enabled=false", "adera.partner.gateway-secret=gw-secret",
		"adera.notification.dev-endpoints=true", "adera.notification.sms-provider=console", "adera.notification.email-provider=mailpit" })
@Import(TestcontainersConfiguration.class)
class WorkerResilienceTests {

	@Autowired JdbcClient jdbc;
	@Autowired CallbackWorker callbacks;
	@Autowired DeliveryWorker deliveries;
	@MockitoBean CallbackSender sender;
	@MockitoBean UserDirectoryClient directory;

	private UUID notification(String status, int attempts, Instant expiresAt) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO notification (id, app_id, environment, external_id, request_hash, user_id, user_alias, template_id, template_version,
				  params, data, kind, channel, status, attempts, next_attempt_at, expires_at, created_at, updated_at, locked_until)
				VALUES (:id, :app, 'DEV', :ext, 'h', :user, 'u_p_someone', :tpl, 1, '{}'::jsonb, '{}'::jsonb, 'DIRECT', 'SMS', :st, :attempts,
				  now() - interval '1 minute', :exp, now(), now(), now() - interval '1 minute')""")
			.param("id", id).param("app", UUID.randomUUID()).param("ext", "e-" + id).param("user", UUID.randomUUID()).param("tpl", UUID.randomUUID())
			.param("st", status).param("attempts", attempts).param("exp", java.sql.Timestamp.from(expiresAt)).update();
		return id;
	}

	private UUID callback(UUID notificationId, String status, int attempts, Instant createdAt) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO callback (id, notification_id, url, payload, status, attempts, next_attempt_at, locked_until, created_at, updated_at)
				VALUES (:id, :n, 'https://partner.example/hook', '{}'::jsonb, :st, :a, now() - interval '1 minute', now() - interval '1 minute', :created, :created)""")
			.param("id", id).param("n", notificationId).param("st", status).param("a", attempts).param("created", java.sql.Timestamp.from(createdAt)).update();
		return id;
	}

	private Map<String, Object> row(String table, UUID id) {
		return jdbc.sql("SELECT status, attempts, next_attempt_at, EXTRACT(EPOCH FROM (next_attempt_at - now())) AS wait FROM " + table + " WHERE id = :id")
			.param("id", id).query().singleRow();
	}

	@Test
	void aFailedCallbackWaitsAtLeastTheScheduleAndAPartnersRetryAfterLengthensIt() {
		UUID n = notification("SENT", 1, Instant.now().plus(Duration.ofDays(1)));
		UUID plain = callback(n, "PENDING", 0, Instant.now());
		UUID asked = callback(n, "PENDING", 0, Instant.now());
		when(sender.send(any(), anyString(), anyString())).thenAnswer(inv -> new CallbackSender.Outcome(false, false, 503, "http_503", Duration.ZERO));

		callbacks.runOnce();
		long wait = ((Number) row("callback", plain).get("wait")).longValue();
		assertThat(wait).as("first retry in about a minute").isBetween(50L, 62L);
		assertThat(row("callback", plain).get("status")).isEqualTo("PENDING");

		// the partner asks for ten minutes
		jdbc.sql("UPDATE callback SET next_attempt_at = now() - interval '1 second' WHERE id = :id").param("id", asked).update();
		when(sender.send(any(), anyString(), anyString())).thenAnswer(inv -> new CallbackSender.Outcome(false, false, 429, "http_429", Duration.ofMinutes(10)));
		callbacks.runOnce();
		assertThat(((Number) row("callback", asked).get("wait")).longValue()).isBetween(570L, 601L);
	}

	@Test
	void aCallbackIsGivenUpOnAfterTwentyFourHoursOrTheAttemptCap() {
		UUID n = notification("SENT", 1, Instant.now().plus(Duration.ofDays(1)));
		UUID old = callback(n, "PENDING", 9, Instant.now().minus(Duration.ofHours(25)));
		UUID tooMany = callback(n, "PENDING", RetryPolicy.MAX_CALLBACK_ATTEMPTS - 1, Instant.now());
		when(sender.send(any(), anyString(), anyString())).thenAnswer(inv -> new CallbackSender.Outcome(false, false, 500, "http_500"));
		callbacks.runOnce();
		assertThat(row("callback", old).get("status")).as("past the 24 h window").isEqualTo("FAILED");
		assertThat(row("callback", tooMany).get("status")).as("attempt cap").isEqualTo("FAILED");
	}

	@Test
	void aCallbackThatBlowsUpIsBackedOffNotLeftToBeReclaimedEveryMinute() {
		UUID n = notification("SENT", 1, Instant.now().plus(Duration.ofDays(1)));
		UUID poison = callback(n, "PENDING", 0, Instant.now());
		when(sender.send(any(), anyString(), anyString())).thenThrow(new IllegalStateException("boom"));
		callbacks.runOnce();
		Map<String, Object> r = row("callback", poison);
		assertThat(r.get("status")).isEqualTo("PENDING");
		assertThat(jdbc.sql("SELECT last_error FROM callback WHERE id = :id").param("id", poison).query(String.class).single()).isEqualTo("internal_error");
		assertThat(((Number) r.get("wait")).longValue()).isGreaterThan(45L);
		assertThat(callbacks.runOnce()).as("nothing is due again right away").isZero();
	}

	@Test
	void aCallbackClaimedAgainAndAgainWithoutFinishingIsFailedWithoutAnotherSend() {
		UUID n = notification("SENT", 1, Instant.now().plus(Duration.ofDays(1)));
		// stale PROCESSING claim (a worker died) that already used every attempt
		UUID stuck = callback(n, "PROCESSING", RetryPolicy.MAX_CALLBACK_ATTEMPTS, Instant.now());
		callbacks.runOnce();
		assertThat(row("callback", stuck).get("status")).isEqualTo("FAILED");
		verify(sender, never()).send(org.mockito.ArgumentMatchers.eq(stuck), anyString(), anyString());
	}

	@Test
	void aDeliveryThatBlowsUpIsRetriedWithBackoffAndNotRightAway() {
		UUID id = notification("QUEUED", 0, Instant.now().plus(Duration.ofDays(1)));
		when(directory.context(any(), any())).thenThrow(new IllegalStateException("boom"));
		deliveries.runOnce();
		Map<String, Object> r = row("notification", id);
		assertThat(r.get("status")).isEqualTo("RETRYING");
		assertThat(jdbc.sql("SELECT reason FROM notification WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("internal_error");
		assertThat(((Number) r.get("wait")).longValue()).as("about 30 s, never immediate").isBetween(25L, 34L);
		assertThat(deliveries.runOnce()).as("not due again right away").isZero();
	}

	@Test
	void aDeliveryThatKeepsFailingIsGivenUpOnAfterTheAttemptCap() {
		UUID id = notification("RETRYING", RetryPolicy.MAX_DELIVERY_ATTEMPTS - 1, Instant.now().plus(Duration.ofDays(1)));
		when(directory.context(any(), any())).thenThrow(new IllegalStateException("boom"));
		deliveries.runOnce();
		assertThat(row("notification", id).get("status")).isEqualTo("FAILED");
	}

	@Test
	void aDeliveryReclaimedAfterWorkersKeptDyingIsFailedInsteadOfLoopingForever() {
		UUID id = notification("PROCESSING", RetryPolicy.MAX_DELIVERY_ATTEMPTS, Instant.now().plus(Duration.ofDays(1)));
		deliveries.runOnce();
		assertThat(row("notification", id).get("status")).isEqualTo("FAILED");
		verify(directory, never()).context(any(), any());
	}

}
