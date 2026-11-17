package top.yealike.pep.control.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.yealike.pep.control.access.mapper.IdentityMapper;
import top.yealike.pep.control.access.mapper.OpsUserMapper;
import top.yealike.pep.control.alarms.mapper.AlarmRecordMapper;
import top.yealike.pep.control.alarms.mapper.AlarmRuleMapper;
import top.yealike.pep.control.audit.mapper.AuditLogMapper;
import top.yealike.pep.control.charging.mapper.BillMapper;
import top.yealike.pep.control.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.control.charging.mapper.CommandRecordMapper;
import top.yealike.pep.control.charging.mapper.RateModelMapper;
import top.yealike.pep.control.charging.mapper.StationMapper;
import top.yealike.pep.control.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.control.devices.mapper.DeviceEventLogMapper;
import top.yealike.pep.control.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.control.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.control.devices.mapper.DevicePropertyTsMapper;
import top.yealike.pep.control.devices.mapper.DeviceTagMapper;
import top.yealike.pep.control.devices.mapper.FrameLogMapper;
import top.yealike.pep.control.devices.mapper.ProductMapper;
import top.yealike.pep.control.devices.mapper.ProtocolDocMapper;
import top.yealike.pep.control.devices.mapper.ProtocolDocSectionMapper;
import top.yealike.pep.control.devices.mapper.ProtocolDocumentMapper;
import top.yealike.pep.control.diagnostic.mapper.TroubleCaseMapper;
import top.yealike.pep.control.telemetry.TdengineMapper;
import top.yealike.pep.control.telemetry.mapper.OrderTelemetryMapper;

class MapperXmlBindingTest {

    @Test
    void everyMapperMethodHasAnXmlStatement() throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(new UnpooledDataSource());
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        Configuration configuration = factory.getObject().getConfiguration();
        for (Class<?> mapper : List.of(
                AlarmRecordMapper.class,
                AlarmRuleMapper.class,
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
