package top.yealike.pep.ops.support.page;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PageSupportTest {

    @Test
    void placesNullTimestampsAfterRowsThatHaveReported() {
        PageSupport.PageQuery paging = PageSupport.pageable(0, 20, "-lastSeen", Set.of("id", "lastSeen"));

        assertEquals("last_seen DESC NULLS LAST", paging.orderBy());
    }
}
