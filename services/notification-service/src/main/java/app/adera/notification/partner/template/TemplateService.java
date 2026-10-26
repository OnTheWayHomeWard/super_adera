package app.adera.notification.partner.template;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.admin.AuditClient;
import app.adera.notification.partner.admin.StaffActor;
import app.adera.notification.partner.auth.RegistryClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {

	/** A template with the values filled in, for one language. {@code emailHtml} is null when the template has none. */
	public record Rendered(String title, String body, String sms, String emailSubject, String emailHtml) {
	}

	private final TemplateRepository repository;
	private final RegistryClient registry;
	private final Clock clock;
	private final AuditClient audit;

	public TemplateService(TemplateRepository repository, RegistryClient registry, Clock clock, AuditClient audit) {
		this.repository = repository;
		this.registry = registry;
		this.clock = clock;
		this.audit = audit;
	}

	@Transactional
	public Template create(Map<String, Object> body, StaffActor actor) {
		UUID staffId = actor.id();
		Instant now = clock.instant();
		UUID appId = appId(body.get("appId"));
		TemplateRules.Parsed p = TemplateRules.parse(body, true, null, List.of(), now);
		if (registry.context(appId).isEmpty()) {
			throw ApiException.validation("appId", "no such mini app");
		}
		if (repository.existsByAppIdAndCode(appId, p.code())) {
			throw new ApiException(409, "template_code_taken");
		}
		Template t = new Template(appId, p.code(), p.kind(), p.category(), staffId, now);
		t.setParams(p.params());
		t.setContent(p.content());
		if (p.active() != null) {
			t.setActive(p.active());
		}
		t.setValidFrom(p.validFrom());
		t.setExpiresAt(p.expiresAt());
		Template saved = repository.save(t);
		// inside the transaction: if the entry cannot be written the template is not created
		audit.record(actor.id().toString(), actor.role().name(), "template.create", "template", saved.getId().toString(),
				Map.of("appId", appId.toString(), "code", p.code(), "kind", p.kind(), "category", p.category()), actor.ip());
		return saved;
	}

	/** Changing the words or the declared values bumps the version; kind and code never change. */
	@Transactional
	public Template update(UUID id, Map<String, Object> body, StaffActor actor) {
		UUID staffId = actor.id();
		Template t = get(id);
		Instant now = clock.instant();
		TemplateRules.Parsed p = TemplateRules.parse(body, false, t.getKind(), t.getParams(), now);
		boolean contentChanged = false;
		if (p.present().contains("params")) {
			var errors = TemplateRules.placeholderErrors(p.content() != null ? p.content() : t.getContent(), p.params());
			if (!errors.isEmpty()) {
				throw ApiException.validation(errors);
			}
			t.setParams(p.params());
			contentChanged = true;
		}
		if (p.present().contains("content")) {
			t.setContent(p.content());
			contentChanged = true;
		}
		if (p.present().contains("category")) {
			t.setCategory(p.category());
		}
		if (p.active() != null) {
			t.setActive(p.active());
		}
		if (p.present().contains("validFrom")) {
			t.setValidFrom(p.validFrom());
		}
		if (p.present().contains("expiresAt")) {
			t.setExpiresAt(p.expiresAt());
		}
		if (t.getValidFrom() != null && t.getExpiresAt() != null && !t.getExpiresAt().isAfter(t.getValidFrom())) {
			throw ApiException.validation("expiresAt", "must be after validFrom");
		}
		if (contentChanged) {
			t.bumpVersion();
		}
		t.touch(staffId, now);
		Map<String, Object> meta = new java.util.LinkedHashMap<>();
		meta.put("appId", t.getAppId().toString());
		meta.put("code", t.getCode());
		meta.put("fields", new java.util.ArrayList<>(new java.util.TreeSet<>(body.keySet())));
		if (p.active() != null) {
			meta.put("active", p.active());
		}
		audit.record(actor.id().toString(), actor.role().name(), "template.update", "template", id.toString(), meta, actor.ip());
		return t;
	}

	@Transactional(readOnly = true)
	public Template get(UUID id) {
		return repository.findById(id).orElseThrow(() -> new ApiException(404, "not_found"));
	}

	@Transactional(readOnly = true)
	public List<Template> list(UUID appId) {
		return repository.findByAppIdOrderByCodeAsc(appId);
	}

	/** For partners: a template is only visible to the app it belongs to. */
	@Transactional(readOnly = true)
	public Template getForApp(UUID id, UUID appId) {
		return repository.findByIdAndAppId(id, appId).orElseThrow(() -> new ApiException(404, "template_not_found"));
	}

	/** Column sizes of inbox_item: a title or body that does not fit would make the delivery fail. */
	public static final int INBOX_TITLE_MAX = 200;
	public static final int INBOX_BODY_MAX = 2000;

	/**
	 * Refuses values that would make the inbox title or body (in any language of the template) longer than its column.
	 * Templates limit their own text, but placeholders can add up to 500 characters each.
	 */
	public void checkInboxFits(Template t, Map<String, String> values) {
		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		for (String lang : t.getContent().keySet()) {
			Rendered r = render(t, lang, values);
			if (r.title() != null && r.title().length() > INBOX_TITLE_MAX) {
				fields.put("title", "with these values the title would be longer than " + INBOX_TITLE_MAX + " characters (" + lang + ")");
			}
			if (r.body() != null && r.body().length() > INBOX_BODY_MAX) {
				fields.put("body", "with these values the body would be longer than " + INBOX_BODY_MAX + " characters (" + lang + ")");
			}
		}
		if (!fields.isEmpty()) {
			throw new ApiException(422, "param_invalid", Map.of("fields", fields));
		}
	}

	/** Picks the user's language (falling back to English text by text) and fills in the values. */
	public Rendered render(Template t, String language, Map<String, String> values) {
		Map<String, String> en = t.getContent().getOrDefault("en", Map.of());
		Map<String, String> chosen = t.getContent().getOrDefault(language == null ? "en" : language, en);
		java.util.function.Function<String, String> pick = key -> chosen.getOrDefault(key, en.get(key));
		String title = fill(pick.apply("title"), values, false);
		String body = fill(pick.apply("body"), values, false);
		String sms = pick.apply("sms") == null ? body : fill(pick.apply("sms"), values, false);
		String subject = pick.apply("emailSubject") != null ? fill(pick.apply("emailSubject"), values, false)
				: (title != null && !title.isBlank() ? title : truncate(body, 60));
		String html = pick.apply("emailHtml") == null ? null : fill(pick.apply("emailHtml"), values, true);
		return new Rendered(title, body, sms, subject, html);
	}

	static String fill(String text, Map<String, String> values, boolean escapeHtml) {
		if (text == null) {
			return null;
		}
		Matcher m = TemplateRules.PLACEHOLDER.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String value = values.getOrDefault(m.group(1), "");
			m.appendReplacement(out, Matcher.quoteReplacement(escapeHtml ? escape(value) : value));
		}
		m.appendTail(out);
		return out.toString();
	}

	static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static UUID appId(Object raw) {
		try {
			return UUID.fromString(String.valueOf(raw));
		}
		catch (IllegalArgumentException e) {
			throw ApiException.validation("appId", "required: the mini app's id");
		}
	}

}
