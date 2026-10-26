package app.adera.notification.sms;

import java.util.Map;

/**
 * One non-GSM character forces a whole SMS into UCS-2 (70 chars per segment instead of 160), which multiplies
 * cost and hurts international deliverability. Replace common fancy punctuation with GSM-7 safe equivalents.
 * (Genuinely non-Latin text, such as Amharic, is left alone.)
 */
public final class GsmText {

	private static final Map<Character, String> REPLACEMENTS = Map.ofEntries(
			Map.entry('→', "->"), Map.entry('←', "<-"), Map.entry('↔', "<->"),
			Map.entry('–', "-"), Map.entry('—', "-"),
			Map.entry('‘', "'"), Map.entry('’', "'"),
			Map.entry('“', "\""), Map.entry('”', "\""),
			Map.entry('…', "..."), Map.entry('•', "*"),
			Map.entry(' ', " "), Map.entry('€', "EUR"));

	private GsmText() {
	}

	public static String toGsmSafe(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			String replacement = REPLACEMENTS.get(c);
			if (replacement != null) {
				out.append(replacement);
			}
			else {
				out.append(c);
			}
		}
		return out.toString();
	}

}
