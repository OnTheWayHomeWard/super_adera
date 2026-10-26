package app.adera.registry.app;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.audit.Actor;
import app.adera.registry.audit.AuditService;
import app.adera.registry.auth.Role;
import app.adera.registry.common.Errors;
import app.adera.registry.config.RegistryProperties;
import app.adera.registry.key.PartnerKeyRepository;
import app.adera.registry.storage.LogoImageProcessor;
import app.adera.registry.storage.ObjectStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AppService {

	private final MiniAppRepository apps;
	private final AppConfigRepository config;
	private final AppConfigService configService;
	private final PartnerKeyRepository keys;
	private final ObjectStorage storage;
	private final AuditService audit;
	private final RegistryProperties props;
	private final JsonMapper mapper;
	private final Clock clock;

	public AppService(MiniAppRepository apps, AppConfigRepository config, AppConfigService configService,
			PartnerKeyRepository keys, ObjectStorage storage, AuditService audit, RegistryProperties props,
			JsonMapper mapper, Clock clock) {
		this.apps = apps;
		this.config = config;
		this.configService = configService;
		this.keys = keys;
		this.storage = storage;
		this.audit = audit;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Transactional
	public MiniApp create(Map<String, Object> body, Actor actor) {
		AppPatch patch = AppPatch.parse(body, true, mapper);
		String slug = patch.as("slug");
		if (apps.existsBySlug(slug)) {
			throw new Errors.Conflict("slug_taken");
		}
		Instant now = clock.instant();
		MiniApp app = new MiniApp(UUID.randomUUID(), slug, actor == null ? null : actor.id(), now);
		apply(app, patch);
		// flush first: the environment rows below are inserted with plain SQL and reference this row
		apps.saveAndFlush(app);
		configService.initialiseEnvironments(app.getId());
		audit.record(actor, "app.create", "mini_app", app.getId().toString(), Map.of("slug", slug));
		return app;
	}

	@Transactional
	public MiniApp update(UUID id, Map<String, Object> body, Actor actor) {
		MiniApp app = get(id);
		AppPatch patch = AppPatch.parse(body, false, mapper);
		apply(app, patch);
		app.touch(clock.instant());
		audit.record(actor, "app.update", "mini_app", id.toString(), Map.of("fields", new ArrayList<>(body.keySet())));
		return app;
	}

	/** DRAFT -> LIVE needs a logo and a support email. Reviving a KILLED app is reserved for super admins. */
	@Transactional
	public MiniApp setStatus(UUID id, String raw, Actor actor) {
		MiniApp app = get(id);
		MiniApp.Status target;
		try {
			target = MiniApp.Status.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new Errors.ValidationFailed("status", "must be DRAFT, LIVE, PAUSED or KILLED");
		}
		if (app.getStatus() == MiniApp.Status.KILLED && target != MiniApp.Status.KILLED && (actor == null || actor.role() != Role.SUPER_ADMIN)) {
			throw new Errors.Forbidden("only_super_admin_can_revive_killed_app");
		}
		if (target == MiniApp.Status.LIVE) {
			List<String> missing = new ArrayList<>();
			if (app.getLogoImageId() == null) {
				missing.add("logo");
			}
			if (app.getSupportEmail() == null) {
				missing.add("supportEmail");
			}
			if (!missing.isEmpty()) {
				throw new Errors.Conflict("not_ready_for_live", Map.of("missing", String.join(",", missing)));
			}
		}
		MiniApp.Status before = app.getStatus();
		app.setStatus(target);
		app.touch(clock.instant());
		audit.record(actor, "app.status", "mini_app", id.toString(), Map.of("from", before.name(), "to", target.name()));
		return app;
	}

	@Transactional
	public MiniApp uploadLogo(UUID id, MultipartFile file, Actor actor) {
		MiniApp app = get(id);
		if (file == null || file.isEmpty()) {
			throw new Errors.ValidationFailed("file", "a file is required (multipart field 'file')");
		}
		if (file.getSize() > props.logo().maxBytes()) {
			throw new Errors.TooLarge("logo larger than " + props.logo().maxBytes() + " bytes");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException e) {
			throw new Errors.ValidationFailed("file", "could not read the upload");
		}
		LogoImageProcessor.Processed image = LogoImageProcessor.process(bytes, props.logo().maxPx());
		UUID previous = app.getLogoImageId();
		UUID imageId = UUID.randomUUID();
		storage.put(ObjectStorage.logoKey(id, imageId), image.bytes(), image.contentType());
		app.setLogo(imageId, image.contentType());
		app.touch(clock.instant());
		if (previous != null) {
			storage.delete(ObjectStorage.logoKey(id, previous));
		}
		audit.record(actor, "app.logo", "mini_app", id.toString(), Map.of("imageId", imageId.toString()));
		return app;
	}

	@Transactional
	public MiniApp removeLogo(UUID id, Actor actor) {
		MiniApp app = get(id);
		if (app.getLogoImageId() != null) {
			storage.delete(ObjectStorage.logoKey(id, app.getLogoImageId()));
			app.setLogo(null, null);
			app.touch(clock.instant());
			audit.record(actor, "app.logo.remove", "mini_app", id.toString(), Map.of());
		}
		return app;
	}

	@Transactional(readOnly = true)
	public MiniApp get(UUID id) {
		return apps.findById(id).orElseThrow(() -> new Errors.NotFound("mini app"));
	}

	/** Accepts the id or the slug (links use the slug). */
	@Transactional(readOnly = true)
	public MiniApp getByIdOrSlug(String idOrSlug) {
		try {
			return get(UUID.fromString(idOrSlug));
		}
		catch (IllegalArgumentException e) {
			return apps.findBySlug(idOrSlug).orElseThrow(() -> new Errors.NotFound("mini app"));
		}
	}

	@Transactional(readOnly = true)
	public List<MiniApp> list(MiniApp.Status status) {
		if (status != null) {
			return apps.findByStatusOrderByPriorityAscSlugAsc(status);
		}
		return apps.findAllByOrderByPriorityAscSlugAsc(PageRequest.of(0, 200));
	}

	@Transactional(readOnly = true)
	public AppViews.AppDetail detail(UUID id) {
		MiniApp app = get(id);
		List<AppViews.KeyView> keyViews = keys.findByAppIdOrderByCreatedAtDesc(id).stream()
			.map(k -> new AppViews.KeyView(k.getKeyId(), k.getEnvironment(), k.getAlgorithm(), k.getFingerprint(), k.getLabel(),
					k.getStatus(), k.getExpiresAt(), k.getCreatedAt(), k.getRevokedAt()))
			.toList();
		return new AppViews.AppDetail(view(app), config.capabilities(id), config.payment(id).orElse(null), config.limits(id),
				config.environments(id), keyViews);
	}

	public AppViews.AppView view(MiniApp a) {
		return new AppViews.AppView(a.getId(), a.getSlug(), a.getName(), a.getDescription(), a.getLogoImageId(), logoUrl(a),
				a.getBrandColor(), a.getCategories(), a.getMarkets(),
				new AppViews.Publisher(a.getPublisherName(), a.getPublisherLegalName(), a.getSupportEmail(), a.getSupportPhone(), a.getWebsite()),
				a.getEntryUrl(), a.getAllowedOrigins(), a.getStatus().name(), a.getPriority(), a.getRatingAvg(), a.getRatingCount(),
				a.getExtra(), a.getCreatedAt(), a.getUpdatedAt());
	}

	@Transactional(readOnly = true)
	public AppViews.PublicApp publicView(MiniApp a) {
		List<AppViews.Permission> permissions = config.capabilities(a.getId()).stream()
			.filter(c -> c.state().equals("GRANTED"))
			.map(c -> new AppViews.Permission(c.capability(),
					Capability.byKey(c.capability()).map(Capability::description).orElse(""), c.reason()))
			.toList();
		return new AppViews.PublicApp(a.getId(), a.getSlug(), a.getName(), a.getDescription(), logoUrl(a), a.getBrandColor(),
				a.getCategories(), a.getMarkets(),
				new AppViews.Publisher(a.getPublisherName(), a.getPublisherLegalName(), a.getSupportEmail(), a.getSupportPhone(), a.getWebsite()),
				a.getEntryUrl(), a.getRatingAvg(), a.getRatingCount(), permissions);
	}

	private String logoUrl(MiniApp a) {
		return a.getLogoImageId() == null ? null
				: storage.presignedGetUrl(ObjectStorage.logoKey(a.getId(), a.getLogoImageId()), Duration.ofMinutes(props.storage().presignMinutes()));
	}

	@SuppressWarnings("unchecked")
	private static void apply(MiniApp app, AppPatch p) {
		if (p.has("name")) app.setName(p.as("name"));
		if (p.has("description")) app.setDescription(p.as("description"));
		if (p.has("brandColor")) app.setBrandColor(p.as("brandColor"));
		if (p.has("categories")) app.setCategories(p.as("categories"));
		if (p.has("markets")) app.setMarkets(p.as("markets"));
		if (p.has("publisherName")) app.setPublisherName(p.as("publisherName"));
		if (p.has("publisherLegalName")) app.setPublisherLegalName(p.as("publisherLegalName"));
		if (p.has("supportEmail")) app.setSupportEmail(p.as("supportEmail"));
		if (p.has("supportPhone")) app.setSupportPhone(p.as("supportPhone"));
		if (p.has("website")) app.setWebsite(p.as("website"));
		if (p.has("entryUrl")) app.setEntryUrl(p.as("entryUrl"));
		if (p.has("allowedOrigins")) app.setAllowedOrigins(p.as("allowedOrigins"));
		if (p.has("priority")) app.setPriority(p.as("priority"));
		if (p.has("extra")) {
			Map<String, Object> merged = new LinkedHashMap<>(app.getExtra());
			((Map<String, Object>) p.get("extra")).forEach((k, v) -> {
				if (v == null) {
					merged.remove(k);
				}
				else {
					merged.put(k, v);
				}
			});
			app.setExtra(merged);
		}
	}

}
