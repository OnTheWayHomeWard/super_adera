package app.adera.notification.email;

/** One transactional email. {@code html} may be null. */
public record EmailMessage(String to, String subject, String text, String html) {
}
