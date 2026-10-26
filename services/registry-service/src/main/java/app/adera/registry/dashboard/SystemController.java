package app.adera.registry.dashboard;

import java.util.Map;

import app.adera.registry.common.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Facts about this deployment for the dashboard. Any staff role. */
@RestController
@RequestMapping("/admin/api/v1")
public class SystemController {

	private final Environment deployment;

	public SystemController(Environment deployment) {
		this.deployment = deployment;
	}

	/** Which environment this deployment is (ADERA_ENV): the other one is stored but unused here. */
	@GetMapping("/system")
	public Map<String, Object> system() {
		return Map.of("environment", deployment.name());
	}

}
