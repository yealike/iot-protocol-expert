package top.yealike.pep.observability;

import java.util.regex.Pattern;

/** 统一的文本脱敏与长度限制，供 Trace 内容策略和评估证据复用。 */
public final class SensitiveDataSanitizer {

    private static final String SENSITIVE_KEY_NAMES =
            "(?:api[_-]?key|x[_-]?api[_-]?key|password|passwd|pwd|secret|client[_-]?secret"
                    + "|private[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|token"
                    + "|authorization|cookie|session|credential|_ipe_context)";
    private static final Pattern JSON_SECRET =
            Pattern.compile("(?i)(\"" + SENSITIVE_KEY_NAMES + "\"\\s*:\\s*\")" + "((?:\\\\.|[^\"\\\\])*)(\")");
    private static final Pattern UNTERMINATED_JSON_SECRET =
            Pattern.compile("(?i)(\"" + SENSITIVE_KEY_NAMES + "\"\\s*:\\s*\")" + "((?:\\\\.|[^\"\\\\])*)$");
    private static final Pattern AUTHORIZATION_HEADER =
            Pattern.compile("(?i)(\\b(?:proxy-)?authorization\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)(\\b(?:set-)?cookie\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern KEY_VALUE_SECRET =
            Pattern.compile("(?i)(\\b" + SENSITIVE_KEY_NAMES + ")" + "(\\s*[=:]\\s*)([^\\s,;&\"']+)");
    private static final Pattern QUOTED_KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(\\b" + SENSITIVE_KEY_NAMES + ")" + "(\\s*[=:]\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");
    private static final Pattern UNTERMINATED_QUOTED_KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(\\b" + SENSITIVE_KEY_NAMES + ")" + "(\\s*[=:]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*|'(?:\\\\.|[^'\\\\])*)$");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern BASIC = Pattern.compile("(?i)(Basic\\s+)[A-Za-z0-9+/=._~-]+");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(://)[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern PRIVATE_KEY =
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final String TRUNCATION_MARKER = "...[truncated]";

    private SensitiveDataSanitizer() {}

    /**
     * 脱敏常见凭据，并按字符数截断。非正数表示不截断。
     *
     * @param value 原始文本
     * @param maxChars 最大字符数
     * @return 脱敏后的文本
     */
    public static String sanitize(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String result = PRIVATE_KEY.matcher(value).replaceAll("***[private-key]***");
        result = AUTHORIZATION_HEADER.matcher(result).replaceAll("$1***");
        result = COOKIE_HEADER.matcher(result).replaceAll("$1***");
        result = JSON_SECRET.matcher(result).replaceAll("$1***$3");
        result = UNTERMINATED_JSON_SECRET.matcher(result).replaceAll("$1***");
        result = QUOTED_KEY_VALUE_SECRET.matcher(result).replaceAll("$1$2***");
        result = UNTERMINATED_QUOTED_KEY_VALUE_SECRET.matcher(result).replaceAll("$1$2***");
        result = KEY_VALUE_SECRET.matcher(result).replaceAll("$1$2***");
        result = BEARER.matcher(result).replaceAll("$1***");
        result = BASIC.matcher(result).replaceAll("$1***");
        result = API_KEY.matcher(result).replaceAll("sk-***");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1***@");
        if (maxChars > 0 && result.length() > maxChars) {
            int markerChars = TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length());
            boolean includeMarker = maxChars > markerChars + 1;
            int contentChars = includeMarker ? maxChars - markerChars : maxChars;
            int end = result.offsetByCodePoints(0, Math.min(contentChars, result.codePointCount(0, result.length())));
            result = result.substring(0, end) + (includeMarker ? TRUNCATION_MARKER : "");
        }
        return result;
    }
}
