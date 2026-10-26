package app.adera.registry.common;

import java.util.Map;

/** Exceptions mapped to HTTP statuses by {@link ApiExceptionHandler}. */
public final class Errors {

	private Errors() {
	}

	/** 400: well-formed request, unacceptable content; one message per field. */
	public static class ValidationFailed extends RuntimeException {

		private final Map<String, String> fields;

		public ValidationFailed(Map<String, String> fields) {
			super("validation_failed " + fields);
			this.fields = fields;
		}

		public ValidationFailed(String field, String message) {
			this(Map.of(field, message));
		}

		public Map<String, String> fields() {
			return fields;
		}

	}

	/** 404 */
	public static class NotFound extends RuntimeException {

		public NotFound(String what) {
			super(what + " not found");
		}

	}

	/** 403: authenticated but not allowed (role too low, etc.). */
	public static class Forbidden extends RuntimeException {

		private final String code;

		public Forbidden(String code) {
			super(code);
			this.code = code;
		}

		public String code() {
			return code;
		}

	}

	/** 409: the request conflicts with the current state. */
	public static class Conflict extends RuntimeException {

		private final String code;
		private final Map<String, String> details;

		public Conflict(String code, Map<String, String> details) {
			super(code);
			this.code = code;
			this.details = details;
		}

		public Conflict(String code) {
			this(code, Map.of());
		}

		public String code() {
			return code;
		}

		public Map<String, String> details() {
			return details;
		}

	}

	/** 413 */
	public static class TooLarge extends RuntimeException {

		public TooLarge(String message) {
			super(message);
		}

	}

}
