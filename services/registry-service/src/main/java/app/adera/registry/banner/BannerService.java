package app.adera.registry.banner;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.adera.registry.app.AppService;
import app.adera.registry.app.MiniApp;
import app.adera.registry.app.MiniAppRepository;
import app.adera.registry.audit.Actor;
import app.adera.registry.audit.AuditService;
import app.adera.registry.common.Errors;
import app.adera.registry.config.RegistryProperties;
import app.adera.registry.storage.BannerImageProcessor;
import app.adera.registry.storage.ObjectStorage;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Banners: staff management (every change audited) and the computed state and visibility. */
@Service
public class BannerService {

	/** Admin list filters; null = any. */
	public record Filter(Banner.Placement placement, Banner.Category category, BannerState state, UUID appId) {
	}

	static final Sort ORDER = Sort.by(Sort.Order.desc("priority"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

	private final BannerRepository banners;
	private final MiniAppRepository apps;
	private final AppService appService;
	private final ExternalDomains domains;
	private final ObjectStorage storage;
	private final AuditService audit;
	private final RegistryProperties props;
	private final Clock clock;

	public BannerService(BannerRepository banners, MiniAppRepository apps, AppService appService, ExternalDomains domains,
			ObjectStorage storage, AuditService audit, RegistryProperties props, Clock clock) {
		this.banners = banners;
		this.apps = apps;
		this.appService = appService;
		this.domains = domains;
		this.storage = storage;
		this.audit = audit;
		this.props = props;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------------ reads

	@Transactional(readOnly = true)
	public Banner get(UUID id) {
		return banners.findById(id).orElseThrow(() -> new Errors.NotFound("banner"));
	}

	@Transactional(readOnly = true)
	public Page<Banner> list(Filter f, int page, int size) {
		return banners.findAll(adminSpec(f, clock.instant()), PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), ORDER));
	}

	static Specification<Banner> adminSpec(Filter f, Instant now) {
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			if (f.placement() != null) {
				where.add(cb.equal(root.get("placement"), f.placement()));
			}
			if (f.category() != null) {
				where.add(cb.equal(root.get("category"), f.category()));
			}
			if (f.appId() != null) {
				where.add(cb.equal(root.get("appId"), f.appId()));
			}
			if (f.state() != null) {
				var published = cb.equal(root.get("status"), Banner.Status.PUBLISHED);
				var started = cb.lessThanOrEqualTo(root.<Instant>get("startsAt"), now);
				var notEnded = cb.or(cb.isNull(root.get("endsAt")), cb.greaterThan(root.<Instant>get("endsAt"), now));
				where.add(switch (f.state()) {
					case DRAFT -> cb.equal(root.get("status"), Banner.Status.DRAFT);
					case PAUSED -> cb.equal(root.get("status"), Banner.Status.PAUSED);
					case SCHEDULED -> cb.and(published, cb.greaterThan(root.<Instant>get("startsAt"), now));
					case LIVE -> cb.and(published, started, notEnded);
					case ENDED -> cb.and(published, cb.lessThanOrEqualTo(root.<Instant>get("endsAt"), now));
				});
			}
			return cb.and(where.toArray(new Predicate[0]));
		};
	}

	/**
	 * What users may see now: PUBLISHED, inside its window, with an image, and no linked app or a LIVE one. The single
	 * definition of "visible"; {@link #visible} is the same rule for one banner already loaded.
	 */
	static Specification<Banner> visibleSpec(Banner.Placement placement, Banner.Category category, Instant now) {
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			where.add(cb.equal(root.get("status"), Banner.Status.PUBLISHED));
			where.add(cb.lessThanOrEqualTo(root.<Instant>get("startsAt"), now));
			where.add(cb.or(cb.isNull(root.get("endsAt")), cb.greaterThan(root.<Instant>get("endsAt"), now)));
			where.add(cb.isNotNull(root.get("imageId")));
			Subquery<UUID> liveApps = query.subquery(UUID.class);
			var app = liveApps.from(MiniApp.class);
			liveApps.select(app.get("id")).where(cb.equal(app.get("status"), MiniApp.Status.LIVE));
			where.add(cb.or(cb.isNull(root.get("appId")), root.get("appId").in(liveApps)));
			if (placement != null) {
				where.add(cb.equal(root.get("placement"), placement));
			}
			if (category != null) {
				where.add(cb.equal(root.get("category"), category));
			}
			return cb.and(where.toArray(new Predicate[0]));
		};
	}

	static boolean visible(Banner b, MiniApp app, Instant now) {
		return BannerState.of(b, now) == BannerState.LIVE && b.getImageId() != null
				&& (b.getAppId() == null || (app != null && app.getStatus() == MiniApp.Status.LIVE));
	}

	// ------------------------------------------------------------------------------------------------ writes

	@Transactional
	public Banner create(Map<String, Object> body, Actor actor) {
		BannerPatch patch = BannerPatch.parse(body, true);
		check(patch, null);
		Instant now = clock.instant();
		Banner b = new Banner(UUID.randomUUID(), actorId(actor), now);
		apply(b, patch);
		banners.saveAndFlush(b);
		Map<String, Object> meta = meta(b);
		meta.put("placement", b.getPlacement().name());
		meta.put("category", b.getCategory().name());
		meta.put("title", b.getTitle().get("en"));
		audit.record(actor, "banner.create", "banner", b.getId().toString(), meta);
		return b;
	}

	@Transactional
	public Banner update(UUID id, Map<String, Object> body, Actor actor) {
		Banner b = get(id);
		BannerPatch patch = BannerPatch.parse(body, false);
		check(patch, b);
		UUID appBefore = b.getAppId();
		apply(b, patch);
		b.touch(actorId(actor), clock.instant());
		banners.flush();
		Map<String, Object> meta = meta(b);
		meta.put("fields", new ArrayList<>(new TreeSet<>(body.keySet())));
		if (patch.has("appId") && appBefore != null && !appBefore.equals(b.getAppId())) {
			meta.put("previousAppId", appBefore.toString());
		}
		audit.record(actor, "banner.update", "banner", id.toString(), meta);
		return b;
	}

	/** DRAFT, PUBLISHED or PAUSED. Publishing needs an image. Setting the status it already has changes nothing. */
	@Transactional
	public Banner setStatus(UUID id, Object raw, Actor actor) {
		Banner b = get(id);
		Banner.Status target = raw instanceof String s ? BannerPatch.parseEnum(Banner.Status.class, s) : null;
		if (target == null) {
			throw new Errors.ValidationFailed("status", "must be " + BannerPatch.choices(Banner.Status.class));
		}
		if (target == b.getStatus()) {
			return b;
		}
		if (target == Banner.Status.PUBLISHED && b.getImageId() == null) {
			throw new Errors.Conflict("not_ready_to_publish", Map.of("missing", "image"));
		}
		Banner.Status before = b.getStatus();
		b.setStatus(target);
		b.touch(actorId(actor), clock.instant());
		Map<String, Object> meta = meta(b);
		meta.put("from", before.name());
		meta.put("to", target.name());
		audit.record(actor, "banner.status", "banner", id.toString(), meta);
		return b;
	}

	/** The given banners get priorities 10 apart so they show in this order (first = highest). Others are untouched. */
	@Transactional
	public List<Banner> reorder(Object rawIds, Actor actor) {
		if (!(rawIds instanceof List<?> list) || list.isEmpty() || list.size() > 100) {
			throw new Errors.ValidationFailed("ids", "must be a list of 1 to 100 banner ids, in the order they should show");
		}
		List<UUID> ids = new ArrayList<>();
		for (Object o : list) {
			try {
				ids.add(UUID.fromString(String.valueOf(o)));
			}
			catch (IllegalArgumentException e) {
				throw new Errors.ValidationFailed("ids", "not a banner id: " + o);
			}
		}
		if (new HashSet<>(ids).size() != ids.size()) {
			throw new Errors.ValidationFailed("ids", "a banner is listed twice");
		}
		Map<UUID, Banner> found = banners.findAllById(ids).stream().collect(Collectors.toMap(Banner::getId, Function.identity()));
		List<String> missing = ids.stream().filter(i -> !found.containsKey(i)).map(UUID::toString).toList();
		if (!missing.isEmpty()) {
			throw new Errors.ValidationFailed("ids", "no such banner: " + String.join(", ", missing));
		}
		Instant now = clock.instant();
		List<Banner> ordered = new ArrayList<>();
		List<Map<String, Object>> changes = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			Banner b = found.get(ids.get(i));
			int priority = (ids.size() - i) * 10;
			if (b.getPriority() != priority) {
				changes.add(Map.of("id", b.getId().toString(), "from", b.getPriority(), "to", priority));
				b.setPriority(priority);
				b.touch(actorId(actor), now);
			}
			ordered.add(b);
		}
		audit.record(actor, "banner.reorder", "banner", null, new LinkedHashMap<>(Map.of("ids", ids.stream().map(UUID::toString).toList(), "changes", changes)));
		return ordered;
	}

	/** Stores the picture in every size under a new image id, then deletes the previous one's files. */
	@Transactional
	public Banner uploadImage(UUID id, MultipartFile file, Actor actor) {
		Banner b = get(id);
		if (file == null || file.isEmpty()) {
			throw new Errors.ValidationFailed("file", "a file is required (multipart field 'file')");
		}
		if (file.getSize() > props.banner().maxBytes()) {
			throw new Errors.TooLarge("image larger than " + props.banner().maxBytes() + " bytes");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException e) {
			throw new Errors.ValidationFailed("file", "could not read the upload");
		}
		Map<String, BannerImageProcessor.Variant> variants = BannerImageProcessor.process(bytes, BannerImages.SIZES);
		UUID previous = b.getImageId();
		UUID imageId = UUID.randomUUID();
		Map<String, Map<String, Integer>> dims = new LinkedHashMap<>();
		variants.forEach((size, v) -> {
			storage.put(ObjectStorage.bannerKey(id, imageId, size), v.bytes(), v.contentType());
			dims.put(size, Map.of("width", v.width(), "height", v.height()));
		});
		b.setImage(imageId, dims);
		b.touch(actorId(actor), clock.instant());
		deleteImageFiles(id, previous);
		Map<String, Object> meta = meta(b);
		meta.put("imageId", imageId.toString());
		BannerImageProcessor.Variant large = variants.get("large");
		meta.put("width", large.width());
		meta.put("height", large.height());
		audit.record(actor, "banner.image", "banner", id.toString(), meta);
		return b;
	}

	@Transactional
	public void delete(UUID id, Actor actor) {
		Banner b = get(id);
		Map<String, Object> meta = meta(b);
		meta.put("title", b.getTitle().get("en"));
		meta.put("placement", b.getPlacement().name());
		meta.put("status", b.getStatus().name());
		banners.delete(b);
		banners.flush();
		deleteImageFiles(b.getId(), b.getImageId());
		audit.record(actor, "banner.delete", "banner", id.toString(), meta);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private void check(BannerPatch patch, Banner current) {
		BannerRules.Candidate c = patch.merge(current);
		Map<String, String> errors = new LinkedHashMap<>(BannerRules.check(c, domains));
		boolean appChanged = patch.has("appId") && c.appId() != null && (current == null || !c.appId().equals(current.getAppId()));
		if (appChanged && !apps.existsById(c.appId())) {
			errors.put("appId", "no such mini app");
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
	}

	private static void apply(Banner b, BannerPatch p) {
		if (p.has("title")) b.setTitle(p.as("title"));
		if (p.has("subtitle")) b.setSubtitle(p.as("subtitle"));
		if (p.has("placement")) b.setPlacement(p.as("placement"));
		if (p.has("category")) b.setCategory(p.as("category"));
		if (p.has("appId")) b.setAppId(p.as("appId"));
		if (p.has("actionType")) b.setActionType(p.as("actionType"));
		if (p.has("target")) b.setTarget(p.as("target"));
		if (p.has("startsAt")) b.setStartsAt(p.as("startsAt"));
		if (p.has("endsAt")) b.setEndsAt(p.as("endsAt"));
		if (p.has("priority")) b.setPriority(p.<Integer>as("priority"));
	}

	/** Carries the linked app so the audit entry also shows in that app's audit view. */
	private static Map<String, Object> meta(Banner b) {
		Map<String, Object> meta = new LinkedHashMap<>();
		if (b.getAppId() != null) {
			meta.put("appId", b.getAppId().toString());
		}
		return meta;
	}

	private static UUID actorId(Actor actor) {
		return actor == null ? null : actor.id();
	}

	void deleteImageFiles(UUID bannerId, UUID imageId) {
		if (imageId == null) {
			return;
		}
		for (String size : BannerImages.SIZES.keySet()) {
			storage.delete(ObjectStorage.bannerKey(bannerId, imageId, size));
		}
	}

	// ------------------------------------------------------------------------------------------------ views

	Map<UUID, MiniApp> appsOf(Collection<Banner> list) {
		Set<UUID> ids = list.stream().map(Banner::getAppId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		return ids.isEmpty() ? Map.of() : apps.findAllById(ids).stream().collect(Collectors.toMap(MiniApp::getId, Function.identity()));
	}

	@Transactional(readOnly = true)
	public BannerViews.AdminBanner view(Banner b) {
		return views(List.of(b)).getFirst();
	}

	@Transactional(readOnly = true)
	public List<BannerViews.AdminBanner> views(List<Banner> list) {
		Map<UUID, MiniApp> appById = appsOf(list);
		Instant now = clock.instant();
		return list.stream().map(b -> {
			MiniApp app = b.getAppId() == null ? null : appById.get(b.getAppId());
			BannerViews.AdminApp appView = app == null ? null
					: new BannerViews.AdminApp(app.getId(), app.getSlug(), app.getName(), appService.logoUrl(app), app.getStatus().name());
			return new BannerViews.AdminBanner(b.getId(), b.getTitle(), b.getSubtitle(), b.getPlacement().name(), b.getCategory().name(),
					b.getAppId(), appView, b.getActionType().name(), b.getTarget(), b.getImageId(), images(b), b.getStartsAt(), b.getEndsAt(),
					b.getPriority(), b.getStatus().name(), BannerState.of(b, now).name(), visible(b, app, now), b.getCreatedBy(),
					b.getUpdatedBy(), b.getCreatedAt(), b.getUpdatedAt());
		}).toList();
	}

	/** Signed links per size, or null without an image. */
	Map<String, BannerViews.Image> images(Banner b) {
		if (b.getImageId() == null || b.getImageVariants() == null) {
			return null;
		}
		Duration validFor = Duration.ofMinutes(props.storage().presignMinutes());
		Map<String, BannerViews.Image> out = new LinkedHashMap<>();
		for (String size : BannerImages.SIZES.keySet()) {
			Map<String, Integer> dims = b.getImageVariants().get(size);
			if (dims != null) {
				out.put(size, new BannerViews.Image(storage.presignedGetUrl(ObjectStorage.bannerKey(b.getId(), b.getImageId(), size), validFor),
						dims.getOrDefault("width", 0), dims.getOrDefault("height", 0)));
			}
		}
		return out;
	}

	/** What the backoffice form needs to check before saving. */
	public Map<String, Object> settings() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("externalDomains", domains.list());
		out.put("placements", names(Banner.Placement.values()));
		out.put("categories", names(Banner.Category.values()));
		out.put("actionTypes", names(Banner.ActionType.values()));
		out.put("statuses", names(Banner.Status.values()));
		out.put("states", names(BannerState.values()));
		out.put("imageSizes", BannerImages.SIZES);
		out.put("maxImageBytes", props.banner().maxBytes());
		return out;
	}

	private static List<String> names(Enum<?>[] values) {
		return java.util.Arrays.stream(values).map(Enum::name).toList();
	}

}
