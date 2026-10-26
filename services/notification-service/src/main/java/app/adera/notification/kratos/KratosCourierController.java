package app.adera.notification.kratos;

import app.adera.notification.dispatch.DispatchResult;
import app.adera.notification.dispatch.NotificationDispatcher;
import app.adera.notification.email.EmailMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the messages Kratos wants to send (login / registration codes) through its HTTP courier.
 * Internal only: this path must never be routed through the public gateway.
 */
@RestController
@RequestMapping("/internal/kratos")
public class KratosCourierController {

	public record SmsRequest(@NotBlank String to, @NotBlank String body, String templateType) {
	}

	public record EmailRequest(@NotBlank String recipient, @NotBlank String subject, String body, String htmlBody,
			String templateType) {
	}

	public record Response(String status, String provider) {
		static Response of(DispatchResult result) {
			return new Response(result.status().name().toLowerCase(), result.provider());
		}
	}

	private final NotificationDispatcher dispatcher;

	public KratosCourierController(NotificationDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@PostMapping("/sms")
	public Response sms(@Valid @RequestBody SmsRequest request) {
		return Response.of(dispatcher.sendSms(request.to(), request.body(), request.templateType()));
	}

	@PostMapping("/email")
	public Response email(@Valid @RequestBody EmailRequest request) {
		EmailMessage message = new EmailMessage(request.recipient(), request.subject(), request.body(),
				request.htmlBody());
		return Response.of(dispatcher.sendEmail(message, request.templateType()));
	}

}
