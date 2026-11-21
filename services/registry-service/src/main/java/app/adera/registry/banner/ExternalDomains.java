package app.adera.registry.banner;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.adera.registry.config.RegistryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The domains an EXTERNAL banner may link to (BANNER_EXTERNAL_DOMAINS, comma separated). A listed domain also allows its
 * subdomains: "adera.et" allows "adera.et" and "www.adera.et", not "notadera.et". Empty = no external banners at all.
 */
@Component
public class ExternalDomains {

	private final List<String> domains;

	@Autowired
	public ExternalDomains(RegistryProperties props) {
		this(props.banner().externalDomains());
	}

	public ExternalDomains(String commaSeparated) {
		this.domains = Arrays.stream(String.valueOf(commaSeparated == null ? "" : commaSeparated).split(","))
			.map(d -> d.trim().toLowerCase(Locale.ROOT))
			.map(d -> d.startsWith("*.") ? d.substring(2) : d)
			.map(d -> d.startsWith(".") ? d.substring(1) : d)
			.map(d -> d.endsWith(".") ? d.substring(0, d.length() - 1) : d)
			.filter(d -> !d.isEmpty())
			.distinct()
			.toList();
	}

	public List<String> list() {
		return domains;
	}

	public boolean allows(String host) {
		if (host == null || host.isBlank()) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		if (h.endsWith(".")) {
			h = h.substring(0, h.length() - 1);
		}
		for (String d : domains) {
			if (h.equals(d) || h.endsWith("." + d)) {
				return true;
			}
		}
		return false;
	}

}
