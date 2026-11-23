package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import top.yealike.pep.ops.charging.domain.RateModel;
import top.yealike.pep.ops.charging.dto.RateModelView;
import top.yealike.pep.ops.charging.mapper.RateModelMapper;
import top.yealike.pep.ops.charging.service.RateModelService;

class RateModelManagementServiceTest {

    @Test
    void savingActiveModelDeactivatesOthersBeforeTargetWrite() {
        RateModelMapper mapper = mock(RateModelMapper.class);
        RateModel oldActive = model("0001", true);
        RateModel target = model("0002", false);
        when(mapper.findAllForUpdate()).thenReturn(List.of(oldActive, target));
        when(mapper.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RateModelService service = new RateModelService(mapper);

        RateModel saved = service.save(input("0002", true, new BigDecimal("1.230000")));

        InOrder order = inOrder(mapper);
        order.verify(mapper).deactivateAllExcept("0002");
        order.verify(mapper).save(target);
        assertTrue(saved.active);
        assertEquals("0002", saved.modelNo);
        assertEquals(new BigDecimal("1.23000"), saved.sharpEnergy);
    }

    @Test
    void activationOnlyChangesTheSelectedExistingModel() {
        RateModelMapper mapper = mock(RateModelMapper.class);
        RateModel oldActive = model("0001", true);
        RateModel target = model("0002", false);
        when(mapper.findAllForUpdate()).thenReturn(List.of(oldActive, target));
        when(mapper.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RateModelService service = new RateModelService(mapper);

        RateModel activated = service.activate("0002");

        verify(mapper).deactivateAllExcept("0002");
        verify(mapper).save(target);
        assertTrue(activated.active);
        assertEquals(new BigDecimal("2.00000"), activated.flatEnergy);
    }

    @Test
    void savingTheActiveModelCannotLeaveTheSystemWithoutAnActiveRate() {
        RateModelMapper mapper = mock(RateModelMapper.class);
        RateModel active = model("0001", true);
        when(mapper.findAllForUpdate()).thenReturn(List.of(active));
        RateModelService service = new RateModelService(mapper);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.save(input("0001", false, new BigDecimal("1.230000"))));

        assertEquals("当前生效费率不能直接停用，请先启用其他费率模型", error.getMessage());
        verify(mapper, never()).deactivateAllExcept(any());
        verify(mapper, never()).save(any());
    }

    @Test
    void activationRejectsUnknownModelWithoutWriting() {
        RateModelMapper mapper = mock(RateModelMapper.class);
        when(mapper.findAllForUpdate()).thenReturn(List.of(model("0001", true)));
        RateModelService service = new RateModelService(mapper);

        assertThrows(IllegalArgumentException.class, () -> service.activate("9999"));

        verify(mapper, never()).deactivateAllExcept(any());
        verify(mapper, never()).save(any());
    }

    @Test
    void rejectsSegmentsThatDoNotCoverExactlyFortyEightHalfHours() {
        RateModelMapper mapper = mock(RateModelMapper.class);
        RateModelService service = new RateModelService(mapper);
        RateModelView shortSchedule = input("0002", false, BigDecimal.ONE, 0, 47);
        RateModelView invalidPeriod = input("0002", false, BigDecimal.ONE, 0, 48, 4);

        assertThrows(IllegalArgumentException.class, () -> service.save(shortSchedule));
        assertThrows(IllegalArgumentException.class, () -> service.save(invalidPeriod));

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsLossRatioOutsidePercentageRange() {
        RateModelMapper mapper = mock(RateModelMapper.class);
        RateModelService service = new RateModelService(mapper);

        assertThrows(IllegalArgumentException.class, () -> service.save(input("0002", false, BigDecimal.ONE, -1, 48)));
        assertThrows(IllegalArgumentException.class, () -> service.save(input("0002", false, BigDecimal.ONE, 101, 48)));

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsNegativePriceExcessPrecisionAndOversizedPrice() {
        RateModelMapper mapper = mock(RateModelMapper.class);
        RateModelService service = new RateModelService(mapper);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(input("0002", false, new BigDecimal("-0.00001"), 0, 48)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(input("0002", false, new BigDecimal("0.000001"), 0, 48)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(input("0002", false, new BigDecimal("42949.67296"), 0, 48)));

        verifyNoInteractions(mapper);
    }

    private static RateModel model(String modelNo, boolean active) {
        RateModel model = new RateModel();
        model.modelNo = modelNo;
        model.sharpEnergy = new BigDecimal("1.00000");
        model.sharpService = new BigDecimal("0.10000");
        model.peakEnergy = new BigDecimal("1.50000");
        model.peakService = new BigDecimal("0.15000");
        model.flatEnergy = new BigDecimal("2.00000");
        model.flatService = new BigDecimal("0.20000");
        model.valleyEnergy = new BigDecimal("0.50000");
        model.valleyService = new BigDecimal("0.05000");
        model.lossRatio = 0;
        model.segments = String.join(",", Collections.nCopies(48, "2"));
        model.active = active;
        return model;
    }

    private static RateModelView input(String modelNo, boolean active, BigDecimal energyPrice) {
        return input(modelNo, active, energyPrice, 0, 48);
    }

    private static RateModelView input(
            String modelNo, boolean active, BigDecimal energyPrice, int lossRatio, int segmentCount) {
        return input(modelNo, active, energyPrice, lossRatio, segmentCount, 2);
    }

    private static RateModelView input(
            String modelNo,
            boolean active,
            BigDecimal energyPrice,
            int lossRatio,
            int segmentCount,
            int lastSegmentValue) {
        List<Integer> segments = new ArrayList<>(Collections.nCopies(segmentCount, 2));
        if (!segments.isEmpty()) {
            segments.set(segments.size() - 1, lastSegmentValue);
        }
        return new RateModelView(
                modelNo,
                energyPrice,
                new BigDecimal("0.10000"),
                new BigDecimal("1.50000"),
                new BigDecimal("0.15000"),
                new BigDecimal("2.00000"),
                new BigDecimal("0.20000"),
                new BigDecimal("0.50000"),
                new BigDecimal("0.05000"),
                lossRatio,
                segments,
                active,
                null);
    }
}
