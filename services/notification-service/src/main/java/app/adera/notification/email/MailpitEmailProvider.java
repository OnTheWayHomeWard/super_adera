package app.adera.notification.email;

import java.io.UnsupportedEncodingException;

import app.adera.notification.config.NotificationProperties;
import app.adera.notification.dispatch.DeliveryException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Dev provider: delivers over SMTP to the local Mailpit inbox (http://localhost:8025). Nothing leaves the machine. */
@Component
@ConditionalOnProperty(prefix = "adera.notification", name = "email-provider", havingValue = "mailpit", matchIfMissing = true)
public class MailpitEmailProvider implements EmailProvider {

	private final JavaMailSender mailSender;
	private final NotificationProperties.Mailpit config;

	public MailpitEmailProvider(JavaMailSender mailSender, NotificationProperties props) {
		this.mailSender = mailSender;
		this.config = props.mailpit();
	}

	@Override
	public String name() {
		return "mailpit";
	}

	@Override
	public boolean isReal() {
		return false;
	}

	@Override
	public void send(EmailMessage message) {
		try {
			MimeMessage mime = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
			helper.setFrom(config.fromEmail(), config.fromName());
			helper.setTo(message.to());
			helper.setSubject(message.subject());
			if (message.html() != null && !message.html().isBlank()) {
				helper.setText(message.text() == null ? "" : message.text(), message.html());
			}
			else {
				helper.setText(message.text() == null ? "" : message.text(), false);
			}
			mailSender.send(mime);
		}
		catch (MessagingException | UnsupportedEncodingException | MailException e) {
			throw new DeliveryException("mailpit_unreachable", e);
		}
	}

}
