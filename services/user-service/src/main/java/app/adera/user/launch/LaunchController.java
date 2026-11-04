package app.adera.user.launch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.user.admin.ApiException;
import app.adera.user.alias.AliasService;
import app.adera.user.consent.ConsentService;
import app.adera.user.profile.NotFoundException;
import app.adera.user.registry.RegistryAppClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/me/apps/{app}/launch}: opening a mini app. No dialog. Gives the pseudonymous id for the app (minted on first
 * use), reconnects it if the user had disconnected, asks the signer for a five-minute RS256 launch token (aud = app id, sub = the
 * pseudonymous id, scopes = what the user has allowed right now) and returns it with the app's URL and the user's states. The
 * scopes are only a hint for the mini app: every data call re-checks the consent, so a revoked permission stops working at once.
 */
@RestController
public class LaunchController {

	private final RegistryAppClient registry;
	private final ConsentService consents;
	private final AliasService aliases;
	private final LaunchSigner signer;

	public LaunchController(RegistryAppClient registry, ConsentService consents, AliasService aliases, LaunchSigner signer) {
		this.registry = registry;
		this.consents = consents;
		this.aliases = aliases;
		this.signer = signer;
	}

	@PostMapping("/api/v1/me/apps/{app}/launch")
	@Transactional
	public Map<String, Object> launch(@RequestHeader("X-User-Id") UUID userId, @PathVariable String app) {
		RegistryAppClient.AppInfo info = registry.app(app).orElseThrow(() -> new NotFoundException("mini app"));
		if ("DRAFT".equals(info.status())) {
			throw new NotFoundException("mini app");
		}
		if (!"LIVE".equals(info.status())) {
			throw new ApiException(403, "app_not_live");
		}
		List<ConsentService.PermissionView> states = consents.openApp(userId, info);   // also checks the user is ACTIVE
		String alias = aliases.mint(info.appId(), userId);
		List<String> scopes = states.stream().filter(p -> p.state().equals("GRANTED")).map(ConsentService.PermissionView::capability).toList();
		LaunchSigner.Signed token = signer.sign(info.appId(), alias, scopes);

		List<Map<String, Object>> list = new ArrayList<>();
		for (ConsentService.PermissionView p : states) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("capability", p.capability());
			row.put("state", p.state());
			list.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("launchToken", token.token());
		out.put("expiresInSeconds", token.expiresInSeconds());
		out.put("url", info.entryUrl() == null || info.entryUrl().isBlank() ? null : info.entryUrl() + (info.entryUrl().contains("?") ? "&" : "?") + "launch=" + token.token());
		out.put("consents", list);
		return out;
	}

}
