package top.yealike.ipe.simulator.runtime;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames.RateModel;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChargingLedgerTest {
    static RateModel rate(){var s=new ArrayList<>(Collections.nCopies(48,2));s.set(20,1);s.set(47,3);s.set(0,0);return new RateModel("32010600000001","0001",new BigDecimal("1.2"),new BigDecimal(".4"),new BigDecimal("1"),new BigDecimal(".35"),new BigDecimal(".8"),new BigDecimal(".3"),new BigDecimal(".5"),new BigDecimal(".2"),0,s);}
    static void eq(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual),actual.toString());}
    @Test void crossesTariffBoundaryWithIndependentElectricityAndServiceFees(){
        var l=new ChargingLedger(rate());l.integrate(LocalDateTime.parse("2026-11-09T09:59:00"),LocalDateTime.parse("2026-11-09T10:01:00"),new BigDecimal("60"));
        eq("1",l.segment(2).energy());eq("1",l.segment(1).energy());eq("2.45",l.amount());eq("2",l.energy());
    }
    @Test void midnightWrapAndLossRatio(){
        var r=rate();r=new RateModel(r.pileCode(),r.modelNo(),r.sharpEnergy(),r.sharpService(),r.peakEnergy(),r.peakService(),r.flatEnergy(),r.flatService(),r.valleyEnergy(),r.valleyService(),10,r.segments());
        var l=new ChargingLedger(r);l.integrate(LocalDateTime.parse("2026-11-09T23:59:00"),LocalDateTime.parse("2026-11-10T00:01:00"),new BigDecimal("60"));
        eq("1",l.segment(3).energy());eq("1",l.segment(0).energy());eq("2.2",l.lossEnergy());eq("2.53",l.amount());
    }
    @Test void highPrecisionIntegrationDoesNotRoundEachTick(){
        var l=new ChargingLedger(rate());var t=LocalDateTime.parse("2026-11-09T09:00:00");for(int i=0;i<3600;i++)l.integrate(t.plusSeconds(i),t.plusSeconds(i+1),new BigDecimal("75"));
        eq("75",l.energy());eq("82.5",l.amount());
    }
}
