package app.adera.user.admin;

/** An error with a fixed HTTP status and a machine code ({"error": code}). */
public class ApiException extends RuntimeException {

	private final int status;
	private final String code;

	public ApiException(int status, String code) {
		super(code);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}

}
