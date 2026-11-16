package top.yealike.ipe.ops.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.yealike.ipe.ops.telemetry.TdengineMapper;

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
