package top.yealike.ipe.protocol.codec;
import top.yealike.ipe.protocol.codec.frames.BmsFrames;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BmsFramesTest {
    @Test void allEightFramesHaveDocumentedWidthsAndEveryFieldRoundTrips(){
        var types=List.of(FrameType.CHARGE_HANDSHAKE,FrameType.PARAM_CONFIG,FrameType.CHARGE_END,FrameType.ERROR_REPORT,FrameType.BMS_ABORT,FrameType.CHARGER_ABORT,FrameType.BMS_DEMAND,FrameType.BMS_INFO);
        int[] lengths={73,45,39,32,28,28,44,31};
        for(int i=0;i<types.size();i++){
            var t=types.get(i);Map<String,Object> values=new LinkedHashMap<>();
            for(var f:BmsFrames.fields(t))values.put(f.name(),switch(f.format()){case "hex"->"01".repeat(f.bytes());case "ascii"->"A";default->new BigDecimal("1").add(BigDecimal.valueOf(f.offset()));});
            var m=new BmsFrames.Message("32010600000001012611090959500001","32010600000001","01",values);
            byte[] body=BmsFrames.encode(t,m);assertEquals(lengths[i],body.length,t.name());
            var decoded=BmsFrames.decode(t,body);assertEquals(m.transactionNo(),decoded.transactionNo());
            assertArrayEquals(body,BmsFrames.encode(t,decoded));
            assertThrows(ProtocolException.class,()->BmsFrames.decode(t,Arrays.copyOf(body,body.length-1)));
        }
    }
    @Test void currentOffsetTemperatureOffsetAndPackedCellGroupUseWireValues(){
        // Header24 + demand=750V, -100A; mode2; measured=750V,-100A; cell=4.1V/group3; SOC20; remain60; output same; elapsed1.
        byte[] header=new BinaryWriter().bcd("0".repeat(32),16).bcd("32010600000001",7).bcd("01",1).toBytes();
        byte[] body=new BinaryWriter().bytes(header).u16(7500).u16(3000).u8(2).u16(7500).u16(3000).u16(0x319A).u8(20).u16(60).u16(7500).u16(3000).u16(1).toBytes();
        var v=BmsFrames.decode(FrameType.BMS_DEMAND,body).values();
        assertEquals(new BigDecimal("-100.0"),v.get("demandCurrent"));assertEquals(new BigDecimal("4.10"),v.get("maxCellVoltage"));assertEquals(3,v.get("maxCellGroup"));
        byte[] info=new BinaryWriter().bytes(header).u8(0).u8(85).u8(0).u8(75).u8(1).u8(0).u8(16).toBytes();
        var b=BmsFrames.decode(FrameType.BMS_INFO,info).values();assertEquals(new BigDecimal("35"),b.get("maxTemperature"));assertEquals(new BigDecimal("1"),b.get("maxCellNo"));
    }
}
