package app.adera.registry.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

/**
 * What Spring's /error page answers for anything that never reached a controller (unknown route, an error raised in a
 * filter, an unexpected failure): the same {@code {"error": "<code>"}} body as everywhere else instead of the default
 * {@code {timestamp, status, error, path}}. Errors raised inside controllers are shaped by the exception handler advice.
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes implements ErrorAttributes {

	@Override
	public Map<String, Object> getErrorAttributes(WebRequest request, org.springframework.boot.web.error.ErrorAttributeOptions options) {
		Object status = request.getAttribute("jakarta.servlet.error.status_code", WebRequest.SCOPE_REQUEST);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("error", code(status instanceof Integer i ? i : 500));
		return out;
	}

	static String code(int status) {
		return switch (status) {
			case 400 -> "invalid_request";
			case 401 -> "unauthorized";
			case 403 -> "forbidden";
			case 404 -> "not_found";
			case 405 -> "method_not_allowed";
			case 406 -> "not_acceptable";
			case 413 -> "payload_too_large";
			case 415 -> "unsupported_media_type";
			case 429 -> "too_many_requests";
			default -> HttpStatus.resolve(status) != null && status < 500 ? "invalid_request" : "internal_error";
		};
	}

}
