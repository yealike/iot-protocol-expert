package top.yealike.pep.ops.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.yealike.pep.ops.access.mapper.IdentityMapper;
import top.yealike.pep.ops.access.mapper.OpsUserMapper;
import top.yealike.pep.ops.audit.mapper.AuditLogMapper;
import top.yealike.pep.ops.charging.mapper.BillMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderSegmentMapper;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.charging.mapper.OrderExternalReferenceMapper;
import top.yealike.pep.ops.charging.mapper.RateModelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceEventLogMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyTsMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.mapper.DeviceTagMapper;
import top.yealike.pep.ops.devices.mapper.FrameLogMapper;
import top.yealike.pep.ops.devices.mapper.ProductMapper;
import top.yealike.pep.ops.devices.mapper.ProtocolDocMapper;
import top.yealike.pep.ops.devices.mapper.ProtocolDocSectionMapper;
import top.yealike.pep.ops.devices.mapper.ProtocolDocumentMapper;
import top.yealike.pep.ops.diagnostic.mapper.TroubleCaseMapper;
import top.yealike.pep.ops.telemetry.TdengineMapper;
import top.yealike.pep.ops.telemetry.mapper.OrderTelemetryMapper;

class MapperXmlBindingTest {

    @Test
    void everyMapperMethodHasAnXmlStatement() throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(new UnpooledDataSource());
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        Configuration configuration = factory.getObject().getConfiguration();
        for (Class<?> mapper : List.of(
                AuditLogMapper.class,
                BillMapper.class,
                ChargeOrderMapper.class,
                ChargeOrderSegmentMapper.class,
                CommandRecordMapper.class,
                DeviceChannelMapper.class,
                DeviceEventLogMapper.class,
                DeviceInstanceMapper.class,
                DevicePropertyLatestMapper.class,
                DevicePropertyTsMapper.class,
                DeviceProtocolMappingMapper.class,
                DeviceTagMapper.class,
                FrameLogMapper.class,
                IdentityMapper.class,
                OpsUserMapper.class,
                OrderTelemetryMapper.class,
                OrderExternalReferenceMapper.class,
                ProductMapper.class,
                ProtocolDocMapper.class,
                ProtocolDocSectionMapper.class,
                ProtocolDocumentMapper.class,
                RateModelMapper.class,
                TroubleCaseMapper.class,
                TdengineMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                if (method.isDefault() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                assertNotNull(
                        configuration.getMappedStatement(mapper.getName() + "." + method.getName()),
                        () -> "Missing XML statement for " + mapper.getSimpleName() + "." + method.getName());
            }
        }
        var advisoryLock = configuration.getMappedStatement(BillMapper.class.getName() + ".lockSourceTransaction");
        assertEquals(SqlCommandType.SELECT, advisoryLock.getSqlCommandType());
        assertEquals(Integer.class, advisoryLock.getResultMaps().getFirst().getType());
        String advisoryLockSql = advisoryLock.getBoundSql(null).getSql();
        assertNotNull(advisoryLockSql);
        advisoryLockSql = advisoryLockSql.toLowerCase();
        org.junit.jupiter.api.Assertions.assertTrue(advisoryLockSql.contains("select 1"));
        org.junit.jupiter.api.Assertions.assertTrue(advisoryLockSql.contains("pg_advisory_xact_lock"));
    }
}
