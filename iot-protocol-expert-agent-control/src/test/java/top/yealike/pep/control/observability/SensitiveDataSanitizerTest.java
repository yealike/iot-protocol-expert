package top.yealike.pep.control.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.yealike.pep.observability.SensitiveDataSanitizer;

class SensitiveDataSanitizerTest {

    @Test
    void redactsQuotedFieldsHeadersCookiesAndEscapedJsonValues() {
        String source = "Authorization: Bearer auth-secret\n"
                + "Cookie: session=cookie-secret; csrftoken=csrf-secret\n"
                + "password='abc def'\n"
                + "{\"client_secret\":\"client-secret\",\"password\":\"abc\\\"rest-secret\"}";

        String sanitized = SensitiveDataSanitizer.sanitize(source, 8192);

        assertThat(sanitized)
                .contains("Authorization: ***")
                .contains("Cookie: ***")
                .contains("password=***")
                .contains("\"client_secret\":\"***\"")
                .contains("\"password\":\"***\"")
                .doesNotContain(
                        "auth-secret", "cookie-secret", "csrf-secret", "abc def", "client-secret", "rest-secret");
    }

    @Test
    void redactsAuthorizationAndCookieHeadersAfterLogPrefixes() {
        String source = "2026-11-18 INFO request Authorization: Bearer auth-secret\n"
                + "2026-11-18 INFO response Set-Cookie: session=cookie-secret; Path=/\n"
                + "2026-11-18 INFO upstream Authorization: Basic dXNlcjpwYXNz";

        String sanitized = SensitiveDataSanitizer.sanitize(source, 8192);

        assertThat(sanitized)
                .contains("INFO request Authorization: ***")
                .contains("INFO response Set-Cookie: ***")
                .contains("upstream Authorization: ***")
                .doesNotContain("auth-secret", "cookie-secret", "Path=/", "dXNlcjpwYXNz");
    }

    @Test
    void keepsTheTruncationMarkerInsideTheConfiguredBudget() {
        String sanitized = SensitiveDataSanitizer.sanitize("料".repeat(100), 24);

        assertThat(sanitized).hasSize(24).endsWith("...[truncated]");
        assertThat(sanitized.codePointCount(0, sanitized.length())).isEqualTo(24);
    }

    @Test
    void redactsInvocationContextFromMapsAndKeyValueText() throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("_ipe_context", "sealed-context");
        arguments.put("_IPE_CONTEXT", "upper-context");
        arguments.put("order_no", "202411130001");
        String source = new ObjectMapper().writeValueAsString(arguments) + "\n_IPE_CONTEXT='quoted context'";

        String sanitized = SensitiveDataSanitizer.sanitize(source, 8192);

        assertThat(sanitized)
                .contains("\"_ipe_context\":\"***\"")
                .contains("\"_IPE_CONTEXT\":\"***\"")
                .contains("_IPE_CONTEXT=***")
                .contains("202411130001")
                .doesNotContain("sealed-context", "upper-context", "quoted context");
    }
}
