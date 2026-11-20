package top.yealike.pep.ops.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.yealike.pep.ops.access.mapper.IdentityMapper;
import top.yealike.pep.ops.access.mapper.OpsUserMapper;
import top.yealike.pep.ops.audit.mapper.AuditLogMapper;
import top.yealike.pep.ops.charging.mapper.BillMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.charging.mapper.RateModelMapper;
import top.yealike.pep.ops.charging.mapper.StationMapper;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceEventLogMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyTsMapper;
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
                CommandRecordMapper.class,
                DeviceChannelMapper.class,
                DeviceEventLogMapper.class,
                DeviceInstanceMapper.class,
                DevicePropertyLatestMapper.class,
                DevicePropertyTsMapper.class,
                DeviceTagMapper.class,
                FrameLogMapper.class,
                IdentityMapper.class,
                OpsUserMapper.class,
                OrderTelemetryMapper.class,
                ProductMapper.class,
                ProtocolDocMapper.class,
                ProtocolDocSectionMapper.class,
                ProtocolDocumentMapper.class,
                RateModelMapper.class,
                StationMapper.class,
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
    }
}
