package top.yealike.pep.control.support.page;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PageSupportTest {
    @Test
    void rejectsOverflowInsteadOfSendingNegativeOffsetToDatabase() {
        assertThrows(
                IllegalArgumentException.class, () -> PageSupport.pageable(Integer.MAX_VALUE, 200, null, Set.of("id")));
        assertEquals(400, PageSupport.pageable(2, 200, null, Set.of("id")).offset());
    }

    @Test
    void ignoresSqlInSortAndCapsPageSize() {
        var query = PageSupport.pageable(0, 999, "id;name desc;id,desc;id);drop table x", Set.of("id"));
        assertEquals(200, query.size());
        assertEquals("id ASC, id DESC", query.orderBy());
    }
}
