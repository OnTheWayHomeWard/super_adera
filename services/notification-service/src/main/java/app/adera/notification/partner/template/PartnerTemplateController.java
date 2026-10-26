package app.adera.notification.partner.template;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import app.adera.notification.partner.auth.PartnerContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A partner sees only its own templates, so it knows what it may send and what each message looks like. Read-only. */
@RestController
@RequestMapping("/v1/templates")
public class PartnerTemplateController {

	private final TemplateService templates;
	private final Clock clock;

	public PartnerTemplateController(TemplateService templates, Clock clock) {
		this.templates = templates;
		this.clock = clock;
	}

	@GetMapping
	public List<TemplateView> list(@RequestAttribute(PartnerContext.ATTR) PartnerContext ctx) {
		return templates.list(ctx.appId()).stream().map(t -> TemplateView.of(t, clock)).toList();
	}

	@GetMapping("/{id}")
	public TemplateView get(@RequestAttribute(PartnerContext.ATTR) PartnerContext ctx, @PathVariable UUID id) {
		return TemplateView.of(templates.getForApp(id, ctx.appId()), clock);
	}

}
