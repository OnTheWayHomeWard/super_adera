package app.adera.registry.banner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The sizes every banner image is stored in: name -> longest side in pixels (never enlarged). */
public final class BannerImages {

	public static final Map<String, Integer> SIZES;

	static {
		Map<String, Integer> sizes = new LinkedHashMap<>();
		sizes.put("small", 480);
		sizes.put("medium", 960);
		sizes.put("large", 1600);
		SIZES = Collections.unmodifiableMap(sizes);
	}

	private BannerImages() {
	}

}
