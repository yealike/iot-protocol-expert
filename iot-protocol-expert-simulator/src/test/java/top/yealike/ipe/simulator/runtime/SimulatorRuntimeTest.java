package top.yealike.ipe.simulator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.embedded.EmbeddedChannel;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.yealike.ipe.protocol.codec.*;
import top.yealike.ipe.protocol.codec.frames.*;
import top.yealike.ipe.simulator.config.SimulatorProperties;

class SimulatorRuntimeTest {
    @TempDir
    Path temp;

    SimulatorRuntime runtime;
    SimulatorProperties props;
    EmbeddedChannel channel;

    @BeforeEach
    void setup() {
        props = new SimulatorProperties();
        props.setAutoConnect(false);
        props.setStateFile(temp.resolve("state.properties").toString());
        runtime = new SimulatorRuntime(
                props,
                new top.yealike.ipe.protocol.telemetry.ProtocolTelemetry(
                        io.micrometer.tracing.Tracer.NOOP,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        runtime.init();
        channel = new EmbeddedChannel();
        runtime.onConnected(channel);
        receive(
                FrameType.LOGIN_ACK,
                RegistrationFrames.encodeLoginAck(new RegistrationFrames.LoginAck(props.getPileCode(), true)));
        receive(FrameType.RATE_RESPONSE, RegistrationFrames.encodeRateModel(ChargingLedgerTest.rate()));
        runtime.configureClock(LocalDateTime.parse("2026-11-09T09:59:00"), 0.0, null);
    }

    @AfterEach
    void close() {
        runtime.shutdown();
        channel.finishAndReleaseAll();
    }

    void receive(FrameType t, byte[] body) {
        runtime.onFrame(new Frame(77, false, t.code(), body));
    }

    String tx(String gun) {
        return props.getPileCode() + gun + "2611090959000001";
    }

    void start(String gun) {
        runtime.plug(gun);
        receive(
                FrameType.REMOTE_START,
                OperationFrames.encodeRemoteStart(new OperationFrames.RemoteStart(
                        tx(gun), props.getPileCode(), gun, "00000000", "00000000D14B0A54", new BigDecimal("1000"))));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> gun() {
        return ((List<Map<String, Object>>) runtime.state().get("guns")).getFirst();
    }

    @Test
    void fullFlowHasBmsStagesAndCrossPeriodTransaction() {
        start("01");
        runtime.configureClock(null, null, 124);
        assertEquals("恒流充电", gun().get("phase"));
        runtime.stopLocal("01");
        var history = (List<?>) runtime.state().get("history");
        var t = (OperationFrames.Transaction) history.getFirst();
        ChargingLedgerTest.eq("2.5", t.totalEnergy());
        assertTrue(t.flat().energy().signum() > 0);
        assertTrue(t.peak().energy().signum() > 0);
        assertEquals(1, t.tradeFlag());
        assertTrue(runtime.logs(200).stream().anyMatch(f -> f.frameTypeHex().equals("0x15")));
        assertTrue(runtime.logs(200).stream().anyMatch(f -> f.frameTypeHex().equals("0x17")));
        assertTrue(runtime.logs(200).stream().anyMatch(f -> f.frameTypeHex().equals("0x19")));
        receive(
                FrameType.TRANSACTION_ACK,
                OperationFrames.encodeTransactionAck(new OperationFrames.TransactionAck(t.transactionNo(), false)));
        assertEquals(1, ((Set<?>) runtime.state().get("pendingTransactions")).size());
        receive(
                FrameType.TRANSACTION_ACK,
                OperationFrames.encodeTransactionAck(new OperationFrames.TransactionAck(t.transactionNo(), true)));
        assertEquals(0, ((Set<?>) runtime.state().get("pendingTransactions")).size());
    }

    @Test
    void disconnectedChargingContinuesAndOutboxSurvivesRestart() {
        start("01");
        runtime.onDisconnected();
        runtime.configureClock(null, null, 64);
        assertEquals(3, gun().get("stateCode"));
        runtime.stopLocal("01");
        assertEquals(1, ((Set<?>) runtime.state().get("pendingTransactions")).size());
        runtime.shutdown();
        var second = new SimulatorRuntime(
                props,
                new top.yealike.ipe.protocol.telemetry.ProtocolTelemetry(
                        io.micrometer.tracing.Tracer.NOOP,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        second.init();
        try {
            assertEquals(1, ((Set<?>) second.state().get("pendingTransactions")).size());
        } finally {
            second.shutdown();
        }
    }

    @Test
    void noPlugRemoteStartIsSupplementedWhenPlugged() {
        receive(
                FrameType.REMOTE_START,
                OperationFrames.encodeRemoteStart(new OperationFrames.RemoteStart(
                        tx("01"), props.getPileCode(), "01", "0", "00000000D14B0A54", new BigDecimal("1000"))));
        assertEquals(2, gun().get("stateCode"));
        runtime.plug("01");
        assertEquals(3, gun().get("stateCode"));
    }

    @Test
    void powerLimitChangesEnergyAndFaultCreatesBill() {
        start("01");
        receive(
                FrameType.PARAM_SET,
                SettingFrames.encodeParamSet(new SettingFrames.ParamSet(props.getPileCode(), true, 50)));
        runtime.configureClock(null, null, 64);
        ChargingLedgerTest.eq(".625", (BigDecimal) gun().get("energy"));
        runtime.fault("01", true);
        assertEquals(1, gun().get("stateCode"));
        var tx = (OperationFrames.Transaction) ((List<?>) runtime.state().get("history")).getFirst();
        assertEquals(0x72, tx.stopReason());
    }

    @Test
    void parallelWaitsForAllAuthorizations() {
        runtime.plug("01");
        runtime.plug("02");
        runtime.startParallel();
        String no = (String) gun().get("parallelNo");
        receive(
                FrameType.PARALLEL_START_ACK,
                ParallelFrames.encodeParallelStartAck(new ParallelFrames.ParallelStartAck(
                        tx("01"), props.getPileCode(), "01", "0", new BigDecimal("1000"), true, 0, no)));
        assertEquals(2, gun().get("stateCode"));
        receive(
                FrameType.PARALLEL_START_ACK,
                ParallelFrames.encodeParallelStartAck(new ParallelFrames.ParallelStartAck(
                        tx("02"), props.getPileCode(), "02", "0", new BigDecimal("1000"), true, 0, no)));
        assertEquals(3, gun().get("stateCode"));
        assertEquals(2, ((List<?>) runtime.state().get("guns")).size());
    }

    @Test
    void offlineCardsReflectSyncClearAndAuthorizeOffline() {
        var c = new OperationFrames.OfflineCard("0000000010000001", "00000000D14B0A54");
        receive(
                FrameType.CARD_SYNC,
                OperationFrames.encodeCardSync(new OperationFrames.CardList(props.getPileCode(), List.of(c))));
        runtime.onDisconnected();
        runtime.plug("01");
        runtime.startOffline("01");
        runtime.configureClock(null, null, 64);
        runtime.stopLocal("01");
        var tx = (OperationFrames.Transaction) ((List<?>) runtime.state().get("history")).getFirst();
        assertEquals(4, tx.tradeFlag());
    }

    @Test
    void fullBatteryStopsAt100RatherThanOnePercentPerSecond() {
        runtime.configureVehicle("01", 1, 99, "LSVAA2A40NX000001", "00000000D14B0A54");
        start("01");
        runtime.configureClock(null, null, 120);
        assertEquals(2, gun().get("stateCode"));
        assertEquals(100, gun().get("soc"));
        var t = (OperationFrames.Transaction) ((List<?>) runtime.state().get("history")).getFirst();
        assertEquals(0x41, t.stopReason());
        ChargingLedgerTest.eq(".01", t.totalEnergy());
    }

    @Test
    void newRateAppliesNextOrderWithoutRepricingCurrentOrder() {
        start("01");
        var r = ChargingLedgerTest.rate();
        var changed = new RegistrationFrames.RateModel(
                r.pileCode(),
                "0002",
                r.sharpEnergy(),
                r.sharpService(),
                r.peakEnergy(),
                r.peakService(),
                new BigDecimal("10"),
                BigDecimal.ZERO,
                r.valleyEnergy(),
                r.valleyService(),
                0,
                r.segments());
        receive(FrameType.RATE_SET, RegistrationFrames.encodeRateModel(changed));
        runtime.configureClock(null, null, 34);
        ChargingLedgerTest.eq(".6875", (BigDecimal) gun().get("amount"));
        assertEquals("0001", gun().get("billingModelNo"));
    }

    @Test
    void crashedActiveCheckpointIsRecoveredAsPowerLossBill() {
        start("01");
        runtime.configureClock(null, null, 34);
        runtime.tick();
        var recovered = new SimulatorRuntime(
                props,
                new top.yealike.ipe.protocol.telemetry.ProtocolTelemetry(
                        io.micrometer.tracing.Tracer.NOOP,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        recovered.init();
        try {
            var history = (List<?>) recovered.state().get("history");
            var t = (OperationFrames.Transaction) history.getFirst();
            assertEquals(0x83, t.stopReason());
            ChargingLedgerTest.eq(".625", t.totalEnergy());
            assertEquals(1, ((Set<?>) recovered.state().get("pendingTransactions")).size());
        } finally {
            recovered.shutdown();
        }
    }

    @Test
    void refusedParallelAuthorizationNeverDeliversEnergy() {
        runtime.plug("01");
        runtime.plug("02");
        runtime.startParallel();
        String no = (String) gun().get("parallelNo");
        receive(
                FrameType.PARALLEL_START_ACK,
                ParallelFrames.encodeParallelStartAck(new ParallelFrames.ParallelStartAck(
                        tx("01"), props.getPileCode(), "01", "0", new BigDecimal("1000"), true, 0, no)));
        receive(
                FrameType.PARALLEL_START_ACK,
                ParallelFrames.encodeParallelStartAck(new ParallelFrames.ParallelStartAck(
                        tx("02"), props.getPileCode(), "02", "0", BigDecimal.ZERO, false, 3, no)));
        runtime.configureClock(null, null, 60);
        assertEquals(2, gun().get("stateCode"));
        ChargingLedgerTest.eq("0", (BigDecimal) gun().get("energy"));
    }

    @Test
    void clearingAbsentFaultDoesNotSilentlyEndCharging() {
        start("01");
        runtime.fault("01", false);
        assertEquals(3, gun().get("stateCode"));
        runtime.configureClock(null, null, 64);
        ChargingLedgerTest.eq("1.25", (BigDecimal) gun().get("energy"));
    }

    @Test
    void bmsTimeoutEmitsErrorBitsAndASettlementBill() {
        start("01");
        runtime.configureClock(null, null, 10);
        runtime.terminate("01", 0x84);
        var frame = runtime.logs(100).stream()
                .filter(f -> f.frameTypeHex().equals("0x1B"))
                .findFirst()
                .orElseThrow();
        var error = BmsFrames.decode(FrameType.ERROR_REPORT, FrameCodec.parseHex(frame.bodyHex()));
        assertEquals(new BigDecimal("1"), error.values().get("chargerChargingTimeout"));
        var tx = (OperationFrames.Transaction) ((List<?>) runtime.state().get("history")).getFirst();
        assertEquals(0x84, tx.stopReason());
    }
}
