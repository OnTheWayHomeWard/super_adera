package app.adera.user.profile;

import java.util.Map;

/** Request was well-formed JSON but not acceptable; carries a message per field. */
public class ValidationFailedException extends RuntimeException {

	private final Map<String, String> fields;

	public ValidationFailedException(Map<String, String> fields) {
		super("validation_failed " + fields);
		this.fields = fields;
	}

	public ValidationFailedException(String field, String message) {
		this(Map.of(field, message));
	}

	public Map<String, String> fields() {
		return fields;
	}

}
