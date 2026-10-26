package app.adera.notification.partner;

import java.util.LinkedHashMap;
import java.util.Map;

/** An error with an HTTP status and a machine-readable code, returned as {@code {"error": code, ...}}. */
public class ApiException extends RuntimeException {

	private final int status;
	private final String code;
	private final Map<String, Object> details;

	public ApiException(int status, String code, Map<String, Object> details) {
		super(code);
		this.status = status;
		this.code = code;
		this.details = details == null ? Map.of() : details;
	}

	public ApiException(int status, String code) {
		this(status, code, Map.of());
	}

	public static ApiException validation(Map<String, String> fields) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("fields", fields);
		return new ApiException(400, "validation_failed", d);
	}

	public static ApiException validation(String field, String message) {
		return validation(Map.of(field, message));
	}

	public int status() { return status; }

	public String code() { return code; }

	public Map<String, Object> details() { return details; }

}
