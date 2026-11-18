package top.yealike.pep.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import org.junit.jupiter.api.Test;

class ToolInvocationEvidenceTest {

    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN " + "PRIVATE KEY-----";
    private static final String PRIVATE_KEY_END = "-----END " + "PRIVATE KEY-----";

    @Test
    void keepsBoundedSanitizedArgumentsAndResult() {
        ToolInvocationEvidence evidence = new ToolInvocationEvidence(32);

        evidence.appendArguments("{\"api_key\":\"secret-value\",\"path\":\"checklist.md\"}");
        evidence.appendResult("FLOW_OK password=hidden-value documents=0 " + "x".repeat(80));

        assertThat(evidence.argumentsEvidence().get("text"))
                .asString()
                .contains("\"api_key\":\"***\"")
                .doesNotContain("secret-value");
        assertThat(evidence.argumentsEvidence().get("truncated")).isEqualTo(true);
        assertThat(evidence.resultEvidence().get("text"))
                .asString()
                .contains("FLOW_OK")
                .doesNotContain("hidden-value");
        assertThat(evidence.resultEvidence().get("truncated")).isEqualTo(true);
    }

    @Test
    void redactsAuthenticationHeadersCookiesAndTruncatedPrivateKeys() {
        ToolInvocationEvidence evidence = new ToolInvocationEvidence(8_000);

        evidence.appendArguments("Authorization: Bearer abc.def.ghi\n"
                + "Cookie: session=abc; csrftoken=def\n"
                + "{\"client_secret\":\"client-value\",\"password\":\"abc\\\"rest\"}");
        evidence.appendResult("before\n" + PRIVATE_KEY_BEGIN + "\n"
                + "secret-private-key-material".repeat(500)
                + "\n"
                + PRIVATE_KEY_END);

        assertThat(evidence.argumentsEvidence().get("text"))
                .asString()
                .contains("Authorization: ***")
                .contains("Cookie: ***")
                .contains("\"client_secret\":\"***\"")
                .contains("\"password\":\"***\"")
                .doesNotContain("abc.def.ghi", "csrftoken=def", "client-value", "abc\\\"rest");
        assertThat(evidence.resultEvidence().get("text"))
                .asString()
                .contains("***[private-key]***")
                .doesNotContain("secret-private-key-material");
    }

    @Test
    void doesNotSplitSurrogatePairsAtTheBoundary() {
        ToolInvocationEvidence evidence = new ToolInvocationEvidence(3);

        evidence.appendResult("ab😀tail");

        assertThat(evidence.resultEvidence().get("text")).isEqualTo("ab");
        assertThat(evidence.resultEvidence().get("truncated")).isEqualTo(true);
    }

    @Test
    void recordsStructuredDataWithoutPersistingBase64Bodies() {
        String secretBody = "c2VjcmV0LWJ1c2luZXNzLWRhdGE=";
        DataBlock block = DataBlock.builder()
                .name("charge-report")
                .source(new Base64Source("application/octet-stream", secretBody))
                .build();
        ToolInvocationEvidence evidence = new ToolInvocationEvidence(8_000);

        evidence.appendData(block);

        assertThat(evidence.resultEvidence().get("text"))
                .asString()
                .contains("\"type\":\"DataBlock\"")
                .contains("\"source\":\"Base64Source\"")
                .contains("\"dataLength\":")
                .doesNotContain(secretBody);
    }
}
