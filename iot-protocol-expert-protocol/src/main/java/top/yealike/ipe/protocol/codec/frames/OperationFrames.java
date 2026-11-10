package top.yealike.ipe.protocol.codec.frames;

import top.yealike.ipe.protocol.codec.BinaryReader;
import top.yealike.ipe.protocol.codec.BinaryWriter;
import top.yealike.ipe.protocol.codec.Numbers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 运营交互帧（0x31~0x48）。 */
public final class OperationFrames {

    private OperationFrames() {
    }

    // ---------------------------------------------------------------- 0x31 桩主动申请启动

    public record StartRequest(String pileCode, String gunNo, int startType, boolean needPassword,
                               String account, String passwordHex, String vin) {
    }

    public static byte[] encodeStartRequest(StartRequest f) {
        return new BinaryWriter()
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .u8(f.startType())
                .u8(f.needPassword() ? 0x01 : 0x00)
                .bytes(padded(f.account(), 8))
                .bytes(padded(f.passwordHex(), 16))
                .ascii(f.vin(), 17)
                .toBytes();
    }

    public static StartRequest decodeStartRequest(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x31 主动申请启动充电");
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        int startType = r.u8();
        boolean needPassword = r.u8() == 0x01;
        String account = hex(r.bytes(8));
        String password = hex(r.bytes(16));
        String vin = r.ascii(17);
        return new StartRequest(pileCode, gunNo, startType, needPassword, account, password, vin);
    }

    // ---------------------------------------------------------------- 0x32 平台确认启动

    public record StartAck(String transactionNo, String pileCode, String gunNo,
                           String logicalCard, BigDecimal balance, boolean success, int failReason) {
    }

    public static byte[] encodeStartAck(StartAck f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16)
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .bcd(f.logicalCard(), 8)
                .u32(Numbers.raw(f.balance(), 2))
                .u8(f.success() ? 0x01 : 0x00)
                .u8(f.failReason())
                .toBytes();
    }

    public static StartAck decodeStartAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x32 确认启动充电");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        String logicalCard = r.bcd(8);
        BigDecimal balance = Numbers.decimal(r.u32(), 2);
        boolean success = r.u8() == 0x01;
        int failReason = r.u8();
        return new StartAck(tx, pileCode, gunNo, logicalCard, balance, success, failReason);
    }

    // ---------------------------------------------------------------- 0x34 远程启动

    public record RemoteStart(String transactionNo, String pileCode, String gunNo,
                              String logicalCard, String physicalCard, BigDecimal balance) {
    }

    public static byte[] encodeRemoteStart(RemoteStart f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16)
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .bcd(f.logicalCard(), 8)
                .bytes(padded(f.physicalCard(), 8))
                .u32(Numbers.raw(f.balance(), 2))
                .toBytes();
    }

    public static RemoteStart decodeRemoteStart(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x34 远程控制启机");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        String logicalCard = r.bcd(8);
        String physicalCard = hex(r.bytes(8));
        BigDecimal balance = Numbers.decimal(r.u32(), 2);
        return new RemoteStart(tx, pileCode, gunNo, logicalCard, physicalCard, balance);
    }

    // ---------------------------------------------------------------- 0x33 远程启动回复

    public record RemoteStartAck(String transactionNo, String pileCode, String gunNo,
                                 boolean success, int failReason) {
    }

    public static byte[] encodeRemoteStartAck(RemoteStartAck f) {
        return new BinaryWriter()
                .bcd(f.transactionNo(), 16)
                .bcd(f.pileCode(), 7)
                .bcd(f.gunNo(), 1)
                .u8(f.success() ? 0x01 : 0x00)
                .u8(f.failReason())
                .toBytes();
    }

    public static RemoteStartAck decodeRemoteStartAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x33 远程启动命令回复");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        boolean success = r.u8() == 0x01;
        int failReason = r.u8();
        return new RemoteStartAck(tx, pileCode, gunNo, success, failReason);
    }

    // ---------------------------------------------------------------- 0x36 / 0x35 远程停机

    public record RemoteStop(String pileCode, String gunNo) {
    }

    public static byte[] encodeRemoteStop(RemoteStop f) {
        return new BinaryWriter().bcd(f.pileCode(), 7).bcd(f.gunNo(), 1).toBytes();
    }

    public static RemoteStop decodeRemoteStop(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x36 远程停机");
        return new RemoteStop(r.bcd(7), r.bcd(1));
    }

    public record StopAck(String pileCode, String gunNo, boolean success, int failReason) {
    }

    public static byte[] encodeStopAck(StopAck f) {
        return new BinaryWriter().bcd(f.pileCode(), 7).bcd(f.gunNo(), 1)
                .u8(f.success() ? 0x01 : 0x00).u8(f.failReason()).toBytes();
    }

    public static StopAck decodeStopAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x35 远程停机命令回复");
        return new StopAck(r.bcd(7), r.bcd(1), r.u8() == 0x01, r.u8());
    }

    // ---------------------------------------------------------------- 0x3B 交易记录

    public record EnergySegment(BigDecimal unitPrice, BigDecimal energy,
                                BigDecimal lossEnergy, BigDecimal amount) {
    }

    /**
     * 交易记录：尖/峰/平/谷四段明细 + 电表起止值 + 总电量金额 + VIN + 停止原因。
     */
    public record Transaction(String transactionNo, String pileCode, String gunNo,
                              LocalDateTime startTime, LocalDateTime endTime,
                              EnergySegment sharp, EnergySegment peak,
                              EnergySegment flat, EnergySegment valley,
                              BigDecimal meterStart, BigDecimal meterEnd,
                              BigDecimal totalEnergy, BigDecimal lossTotalEnergy, BigDecimal totalAmount,
                              String vin, int tradeFlag, LocalDateTime tradeTime,
                              int stopReason, String physicalCard) {
    }

    public static byte[] encodeTransaction(Transaction t) {
        BinaryWriter w = new BinaryWriter()
                .bcd(t.transactionNo(), 16)
                .bcd(t.pileCode(), 7)
                .bcd(t.gunNo(), 1)
                .cp56(t.startTime())
                .cp56(t.endTime());
        for (EnergySegment seg : List.of(t.sharp(), t.peak(), t.flat(), t.valley())) {
            w.u32(Numbers.raw(seg.unitPrice(), 5))
                    .u32(Numbers.raw(seg.energy(), 4))
                    .u32(Numbers.raw(seg.lossEnergy(), 4))
                    .u32(Numbers.raw(seg.amount(), 4));
        }
        return w.uint(Numbers.raw(t.meterStart(), 4), 5)
                .uint(Numbers.raw(t.meterEnd(), 4), 5)
                .u32(Numbers.raw(t.totalEnergy(), 4))
                .u32(Numbers.raw(t.lossTotalEnergy(), 4))
                .u32(Numbers.raw(t.totalAmount(), 4))
                .ascii(t.vin(), 17)
                .u8(t.tradeFlag())
                .cp56(t.tradeTime())
                .u8(t.stopReason())
                .bytes(padded(t.physicalCard(), 8))
                .toBytes();
    }

    public static Transaction decodeTransaction(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x3B 交易记录");
        String tx = r.bcd(16);
        String pileCode = r.bcd(7);
        String gunNo = r.bcd(1);
        LocalDateTime start = r.cp56();
        LocalDateTime end = r.cp56();
        List<EnergySegment> segs = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            segs.add(new EnergySegment(Numbers.decimal(r.u32(), 5), Numbers.decimal(r.u32(), 4),
                    Numbers.decimal(r.u32(), 4), Numbers.decimal(r.u32(), 4)));
        }
        BigDecimal meterStart = Numbers.decimal(r.uint(5), 4);
        BigDecimal meterEnd = Numbers.decimal(r.uint(5), 4);
        BigDecimal totalEnergy = Numbers.decimal(r.u32(), 4);
        BigDecimal lossTotal = Numbers.decimal(r.u32(), 4);
        BigDecimal totalAmount = Numbers.decimal(r.u32(), 4);
        String vin = r.ascii(17);
        int tradeFlag = r.u8();
        LocalDateTime tradeTime = r.cp56();
        int stopReason = r.u8();
        String physicalCard = hex(r.bytes(8));
        return new Transaction(tx, pileCode, gunNo, start, end, segs.get(0), segs.get(1), segs.get(2), segs.get(3),
                meterStart, meterEnd, totalEnergy, lossTotal, totalAmount, vin, tradeFlag, tradeTime,
                stopReason, physicalCard);
    }

    public record TransactionAck(String transactionNo, boolean success) {
    }

    public static byte[] encodeTransactionAck(TransactionAck f) {
        return new BinaryWriter().bcd(f.transactionNo(), 16).u8(f.success() ? 0x00 : 0x01).toBytes();
    }

    public static TransactionAck decodeTransactionAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x40 交易记录确认");
        return new TransactionAck(r.bcd(16), r.u8() == 0x00);
    }

    // ---------------------------------------------------------------- 0x42 / 0x41 余额

    public record BalanceUpdate(String pileCode, String gunNo, String physicalCard, BigDecimal balance) {
    }

    public static byte[] encodeBalanceUpdate(BalanceUpdate f) {
        return new BinaryWriter().bcd(f.pileCode(), 7).bcd(f.gunNo(), 1)
                .bytes(padded(f.physicalCard(), 8)).u32(Numbers.raw(f.balance(), 2)).toBytes();
    }

    public static BalanceUpdate decodeBalanceUpdate(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x42 远程账户余额更新");
        return new BalanceUpdate(r.bcd(7), r.bcd(1), hex(r.bytes(8)), Numbers.decimal(r.u32(), 2));
    }

    public record BalanceAck(String pileCode, String physicalCard, int result) {
    }

    public static byte[] encodeBalanceAck(BalanceAck f) {
        return new BinaryWriter().bcd(f.pileCode(), 7).bytes(padded(f.physicalCard(), 8)).u8(f.result()).toBytes();
    }

    public static BalanceAck decodeBalanceAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x41 余额更新应答");
        return new BalanceAck(r.bcd(7), hex(r.bytes(8)), r.u8());
    }

    // ---------------------------------------------------------------- 离线卡 0x44~0x48

    public record OfflineCard(String logicalCard, String physicalCard) {
    }

    public record CardList(String pileCode, List<OfflineCard> cards) {

        public CardList {
            cards = List.copyOf(cards);
        }
    }

    public static byte[] encodeCardSync(CardList f) {
        BinaryWriter w = new BinaryWriter().bcd(f.pileCode(), 7).u8(f.cards().size());
        for (OfflineCard c : f.cards()) {
            w.bcd(c.logicalCard(), 8).bytes(padded(c.physicalCard(), 8));
        }
        return w.toBytes();
    }

    public static CardList decodeCardSync(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x44 离线卡数据同步");
        String pileCode = r.bcd(7);
        int count = r.u8();
        List<OfflineCard> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cards.add(new OfflineCard(r.bcd(8), hex(r.bytes(8))));
        }
        return new CardList(pileCode, cards);
    }

    public static byte[] encodeCardSyncAck(String pileCode, boolean success, int failReason) {
        return new BinaryWriter().bcd(pileCode, 7).u8(success ? 0x01 : 0x00).u8(failReason).toBytes();
    }

    public record CardSyncAck(String pileCode, boolean success, int failReason) {
    }

    public static CardSyncAck decodeCardSyncAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x43 离线卡数据同步应答");
        return new CardSyncAck(r.bcd(7), r.u8() == 0x01, r.u8());
    }

    public record CardClear(String pileCode, List<String> physicalCards) {
        public CardClear {
            physicalCards = List.copyOf(physicalCards);
        }
    }

    public static byte[] encodeCardClear(CardClear f) {
        BinaryWriter w = new BinaryWriter().bcd(f.pileCode(), 7).u8(f.physicalCards().size());
        for (String card : f.physicalCards()) {
            w.bytes(padded(card, 8));
        }
        return w.toBytes();
    }

    public static CardClear decodeCardClear(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x46 离线卡数据清除");
        String pileCode = r.bcd(7);
        int count = r.u8();
        List<String> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cards.add(hex(r.bytes(8)));
        }
        return new CardClear(pileCode, cards);
    }

    public record CardClearAck(String pileCode, List<CardClearResult> results) {
        public record CardClearResult(String physicalCard, boolean success, int failReason) {
        }

        public CardClearAck {
            results = List.copyOf(results);
        }
    }

    public static byte[] encodeCardClearAck(CardClearAck f) {
        BinaryWriter w = new BinaryWriter().bcd(f.pileCode(), 7);
        for (CardClearAck.CardClearResult res : f.results()) {
            w.bytes(padded(res.physicalCard(), 8)).u8(res.success() ? 0x01 : 0x00).u8(res.failReason());
        }
        return w.toBytes();
    }

    public static CardClearAck decodeCardClearAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x45 离线卡数据清除应答");
        String pileCode = r.bcd(7);
        List<CardClearAck.CardClearResult> results = new ArrayList<>();
        while (r.remaining() >= 10) {
            results.add(new CardClearAck.CardClearResult(hex(r.bytes(8)), r.u8() == 0x01, r.u8()));
        }
        return new CardClearAck(pileCode, results);
    }

    public record CardQuery(String pileCode, List<String> physicalCards) {
        public CardQuery {
            physicalCards = List.copyOf(physicalCards);
        }
    }

    public static byte[] encodeCardQuery(CardQuery f) {
        BinaryWriter w = new BinaryWriter().bcd(f.pileCode(), 7).u8(f.physicalCards().size());
        for (String card : f.physicalCards()) {
            w.bytes(padded(card, 8));
        }
        return w.toBytes();
    }

    public static CardQuery decodeCardQuery(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x48 离线卡数据查询");
        String pileCode = r.bcd(7);
        int count = r.u8();
        List<String> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cards.add(hex(r.bytes(8)));
        }
        return new CardQuery(pileCode, cards);
    }

    public record CardQueryAck(String pileCode, List<CardQueryResult> results) {
        public record CardQueryResult(String physicalCard, boolean exists) {
        }

        public CardQueryAck {
            results = List.copyOf(results);
        }
    }

    public static byte[] encodeCardQueryAck(CardQueryAck f) {
        BinaryWriter w = new BinaryWriter().bcd(f.pileCode(), 7);
        for (CardQueryAck.CardQueryResult res : f.results()) {
            w.bytes(padded(res.physicalCard(), 8)).u8(res.exists() ? 0x01 : 0x00);
        }
        return w.toBytes();
    }

    public static CardQueryAck decodeCardQueryAck(byte[] body) {
        BinaryReader r = new BinaryReader(body, "0x47 离线卡数据查询应答");
        String pileCode = r.bcd(7);
        List<CardQueryAck.CardQueryResult> results = new ArrayList<>();
        while (r.remaining() >= 9) {
            results.add(new CardQueryAck.CardQueryResult(hex(r.bytes(8)), r.u8() == 0x01));
        }
        return new CardQueryAck(pileCode, results);
    }

    // ---------------------------------------------------------------- 工具

    static byte[] padded(String hex, int bytes) {
        byte[] out = new byte[bytes];
        if (hex == null || hex.isBlank()) {
            return out;
        }
        String cleaned = hex.replaceAll("\\s+", "");
        byte[] raw = java.util.HexFormat.of().parseHex(cleaned);
        if(raw.length>bytes)throw new top.yealike.ipe.protocol.codec.ProtocolException("HEX字段过长");
        System.arraycopy(raw, 0, out, bytes-raw.length, raw.length);
        return out;
    }

    static String hex(byte[] data) {
        return java.util.HexFormat.of().withUpperCase().formatHex(data);
    }
}