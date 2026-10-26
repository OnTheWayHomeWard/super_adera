package app.adera.registry.common;

import java.util.Locale;

/**
 * A deployment runs in exactly one environment (setting ADERA_ENV): DEV or PROD. Staff can store allowed addresses and keys
 * for both, but only the one that matches the deployment is active; the other is stored and unused there.
 */
public enum Environment {

	DEV, PROD;

	public static Environment parse(String value) {
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (RuntimeException e) {
			throw new Errors.ValidationFailed("environment", "must be DEV or PROD");
		}
	}

	/** Reads the deployment setting (ADERA_ENV: dev or prod); anything else is a configuration error. */
	public static Environment ofDeployment(String setting) {
		try {
			return valueOf(setting.trim().toUpperCase(Locale.ROOT));
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("ADERA_ENV must be dev or prod, got '" + setting + "'");
		}
	}

}
