package top.yealike.pep.ops.telemetry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class TdengineTelemetryStore {
    private static final String DB = "ipe_telemetry";
    private final SqlSessionTemplate sqlSession;
    private final TdengineMapper mapper;

    public TdengineTelemetryStore(
            @Value("${ipe.ops.tdengine.url}") String url,
            @Value("${ipe.ops.tdengine.username}") String user,
            @Value("${ipe.ops.tdengine.password}") String password) {
        this.sqlSession = new SqlSessionTemplate(factory(url, user, password));
        this.mapper = sqlSession.getMapper(TdengineMapper.class);
    }

    public record Sample(
            long id,
            String orderNo,
            Timestamp time,
            String source,
            BigDecimal voltage,
            BigDecimal current,
            BigDecimal power,
            BigDecimal gunTemp,
            BigDecimal batteryTemp,
            BigDecimal soc) {}

    private static SqlSessionFactory factory(String url, String user, String password) {
        UnpooledDataSource dataSource = new UnpooledDataSource();
        dataSource.setDriver(driverClass(url));
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        try {
            factoryBean.setMapperLocations(
                    new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/tdengine/*.xml"));
            return factoryBean.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("TDengine MyBatis 初始化失败", e);
        }
    }

    private static String driverClass(String url) {
        if (url.startsWith("jdbc:TAOS-WS:")) {
            return "com.taosdata.jdbc.ws.WebSocketDriver";
        }
        if (url.startsWith("jdbc:TAOS-RS:")) {
            return "com.taosdata.jdbc.rs.RestfulDriver";
        }
        return "com.taosdata.jdbc.TSDBDriver";
    }

    private void ensureSchema() {
        mapper.createDatabase(DB);
        mapper.createStable(DB);
    }

    public static String table(String orderNo) {
        try {
            return "o_"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(orderNo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    void write(Sample sample) {
        if (!"DEVICE".equals(sample.source()) && !"RECEIVED".equals(sample.source()))
            throw new IllegalArgumentException("Invalid telemetry time source");
        String table = table(sample.orderNo());
        ensureSchema();
        mapper.createTable(DB, table);
        mapper.insertSample(
                DB,
                table,
                sample.time(),
                sample.source(),
                toDouble(sample.voltage()),
                toDouble(sample.current()),
                toDouble(sample.power()),
                toDouble(sample.gunTemp()),
                toDouble(sample.batteryTemp()),
                toDouble(sample.soc()));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    List<OrderTelemetryService.Point> query(String orderNo) {
        ensureSchema();
        List<OrderTelemetryService.Point> rows = new ArrayList<>();
        for (TdengineMapper.TelemetryRow row : mapper.query(DB, table(orderNo))) {
            rows.add(new OrderTelemetryService.Point(
                    row.ts().toInstant(),
                    row.timeSource(),
                    row.voltage(),
                    row.current(),
                    row.power(),
                    row.gunTemp(),
                    row.batteryTemp(),
                    row.soc()));
        }
        return rows;
    }
}
