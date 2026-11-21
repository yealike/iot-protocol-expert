package top.yealike.pep.ops.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ProtocolDocumentLinkMigrationTest {

    @Test
    void usesExactUniqueDocumentMatchingAndAllowsProtocolsWithoutUploadedDocuments() throws IOException {
        String sql;
        try (InputStream input = Objects.requireNonNull(getClass()
                .getClassLoader()
                .getResourceAsStream("db/migration/V11__exact_charger_protocol_document_links.sql"))) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("status = 'ACTIVE'")
                .contains("upper(protocol_name_en) = 'YKC'")
                .contains("lower(document_version) = '1.6'")
                .contains("upper(protocol_name_en) = 'OCPP'")
                .contains("lower(document_version) = '1.6j'")
                .contains("where p.protocol = 'YKC16'")
                .contains("where p.protocol = 'OCPP16J'")
                .contains("p.protocol_document_id is distinct from ykc_document_id")
                .contains("p.protocol_document_id is distinct from ocpp_document_id");
        assertThat(sql.toLowerCase(Locale.ROOT)).doesNotContain(" like ");
        assertThat(sql)
                .containsPattern("if ykc_document_count > 1 then\\s+raise exception")
                .containsPattern("if ocpp_document_count > 1 then\\s+raise exception")
                .contains("and ykc_document_count = 1")
                .contains("and ocpp_document_count = 1");
    }
}
