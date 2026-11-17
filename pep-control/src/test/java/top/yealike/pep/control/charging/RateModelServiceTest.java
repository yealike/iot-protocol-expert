package top.yealike.pep.control.charging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.api.dto.OpsDtos;
import top.yealike.pep.control.charging.mapper.RateModelMapper;
import top.yealike.pep.control.charging.service.RateModelService;

class RateModelServiceTest {
    @Test
    void invalidFinancialInputsNeverReachStorage() {
        var mapper = mock(RateModelMapper.class);
        var service = new RateModelService(mapper);
        var segments = new ArrayList<>(Collections.nCopies(48, 2));
        for (BigDecimal price :
                new BigDecimal[] {null, new BigDecimal("-1"), new BigDecimal("0.000001"), new BigDecimal("42949.67296")
                }) {
            assertThrows(IllegalArgumentException.class, () -> service.save(input(price, 0, segments)));
        }
        assertThrows(IllegalArgumentException.class, () -> service.save(input(BigDecimal.ONE, 101, segments)));
        segments.set(0, null);
        assertThrows(IllegalArgumentException.class, () -> service.save(input(BigDecimal.ONE, 0, segments)));
        segments.set(0, 4);
        assertThrows(IllegalArgumentException.class, () -> service.save(input(BigDecimal.ONE, 0, segments)));
        verifyNoInteractions(mapper);
    }

    private OpsDtos.RateModelView input(BigDecimal price, int loss, List<Integer> segments) {
        return new OpsDtos.RateModelView(
                "0001",
                price,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                loss,
                segments,
                true,
                null);
    }
}
