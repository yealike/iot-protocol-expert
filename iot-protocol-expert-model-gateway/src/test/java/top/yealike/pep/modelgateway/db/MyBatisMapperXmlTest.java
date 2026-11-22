package top.yealike.pep.modelgateway.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.yealike.pep.modelgateway.catalog.mapper.CatalogMapper;
import top.yealike.pep.modelgateway.quota.QuotaRecords;
import top.yealike.pep.modelgateway.quota.mapper.QuotaMapper;

/** 校验模型网关的 MyBatis 映射文件可解析、Mapper 方法完整绑定且动态参数可生成。 */
class MyBatisMapperXmlTest {

    private static final List<Class<?>> MAPPERS = List.of(QuotaMapper.class, CatalogMapper.class);

    @Test
    void everyMapperMethodHasAnXmlStatement() throws Exception {
        Configuration configuration = configuration();
        for (Class<?> mapperType : MAPPERS) {
            for (Method method : mapperType.getDeclaredMethods()) {
                if (method.isDefault() || method.isSynthetic()) {
                    continue;
                }
                String statementId = mapperType.getName() + "." + method.getName();
                assertThat(configuration.hasStatement(statementId))
                        .as("Mapper 语句缺失：%s", statementId)
                        .isTrue();
            }
        }
    }

    @Test
    void buildsDynamicFiltersAndRecordParameters() throws Exception {
        Configuration configuration = configuration();

        BoundSql filtered = configuration
                .getMappedStatement(QuotaMapper.class.getName() + ".usageRows")
                .getBoundSql(Map.of(
                        "scopeKind", "USER", "subjectId", "u-1", "limit", 100, "modelKey", "m", "projectId", "p"));
        assertThat(filtered.getSql()).contains("c.model_profile_key = ?", "c.project_id = ?");

        BoundSql unfiltered = configuration
                .getMappedStatement(QuotaMapper.class.getName() + ".usageRows")
                .getBoundSql(Map.of("scopeKind", "USER", "subjectId", "u-1", "limit", 100));
        assertThat(unfiltered.getSql()).doesNotContain("c.model_profile_key =", "c.project_id =");

        UUID id = UUID.randomUUID();
        QuotaRecords.Call call = new QuotaRecords.Call(
                id,
                UUID.randomUUID(),
                0,
                "u-1",
                "USER",
                "u-1",
                "t-1",
                "p-1",
                "task-1",
                "run-1",
                "model-1",
                "provider-1",
                "upstream-1",
                "hash-1",
                "HELD");
        BoundSql insertCall = configuration
                .getMappedStatement(QuotaMapper.class.getName() + ".insertCall")
                .getBoundSql(call);
        assertThat(insertCall.getParameterMappings()).hasSize(15);
    }

    private static Configuration configuration() throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(new UnpooledDataSource());
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setJdbcTypeForNull(JdbcType.NULL);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        return factory.getObject().getConfiguration();
    }
}
