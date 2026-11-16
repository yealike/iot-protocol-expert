package top.yealike.ipe.ai.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.yealike.ipe.ai.conversation.ConversationRecords.Message;
import top.yealike.ipe.ai.mybatis.UuidTypeHandler;
import top.yealike.ipe.ai.policy.PolicyService.Rule;
import top.yealike.ipe.ai.run.RunRecords.Run;

class MapperXmlBindingTest {
    private static Configuration configuration;
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final List<Class<?>> MAPPERS = List.of(
            ConversationMapper.class,
            RunMapper.class,
            KnowledgeMapper.class,
            PermissionPolicyMapper.class,
            AuditLogMapper.class);

    @BeforeAll
    static void loadXml() throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(new UnpooledDataSource());
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setCallSettersOnNulls(true);
        config.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        factory.setConfiguration(config);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/ai/*.xml"));
        configuration = factory.getObject().getConfiguration();
    }

    @Test
    void everyMethodBindsAllParametersIncludingNulls() throws Exception {
        for (Class<?> mapper : MAPPERS) {
            for (Method method : mapper.getDeclaredMethods()) {
                MappedStatement statement = configuration.getMappedStatement(mapper.getName() + "." + method.getName());
                for (boolean nullable : List.of(false, true)) {
                    Object[] args = Arrays.stream(method.getParameterTypes())
                            .map(type -> sample(type, nullable))
                            .toArray();
                    Object params = new ParamNameResolver(configuration, method).getNamedParams(args);
                    BoundSql sql = statement.getBoundSql(params);
                    assertFalse(sql.getSql().isBlank(), statement.getId());
                    ParameterHandler handler = configuration.newParameterHandler(statement, params, sql);
                    handler.setParameters(mock(PreparedStatement.class));
                }
            }
        }
    }

    private static Object sample(Class<?> type, boolean nullable) {
        if (type == int.class) return 10;
        if (type == long.class) return 1L;
        if (type == List.class) return List.of();
        if (nullable) return null;
        if (type == UUID.class) return ID;
        if (type == Instant.class) return NOW;
        return "value";
    }

    @Test
    void atomicCountersAreDirtySelectsAndNeverCached() {
        for (String id : List.of("ConversationMapper.nextMessageSeq", "RunMapper.nextEventCursor")) {
            var statement = configuration.getMappedStatement("top.yealike.ipe.ai.mapper." + id);
            assertTrue(statement.isDirtySelect());
            assertTrue(statement.isFlushCacheRequired());
            assertFalse(statement.isUseCache());
            assertTrue(
                    statement.getBoundSql(Map.of("conversationId", ID)).getSql().contains("returning"));
        }
    }

    @Test
    void newRunSqlHasQueuedStatusAndMatchingColumnsAndValues() {
        var statement = configuration.getMappedStatement(RunMapper.class.getName() + ".insertRun");
        String sql = statement.getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("'QUEUED'"));
        assertEquals(18, statement.getBoundSql(Map.of()).getParameterMappings().size());
        assertTrue(sql.contains("created_at, updated_at"));
        assertTrue(sql.matches(".*\\?\\s*,\\s*\\?\\s*\\)\\s*"));
    }

    @Test
    void keywordSqlBindsFiltersAndKeepsKeywordsOutOfSqlText() {
        var statement = configuration.getMappedStatement(KnowledgeMapper.class.getName() + ".candidates");
        String keyword = "x' OR 1=1 --";
        BoundSql filtered = statement.getBoundSql(Map.of(
                "protocolCode", "YKC", "protocolVersion", "1.6", "keywords", List.of(keyword, "心跳"), "limit", 20));
        assertEquals(5, filtered.getParameterMappings().size());
        assertFalse(filtered.getSql().contains(keyword));
        BoundSql unfiltered = statement.getBoundSql(
                Map.of("protocolCode", " ", "protocolVersion", "", "keywords", List.of("心跳"), "limit", 20));
        assertEquals(2, unfiltered.getParameterMappings().size());
        assertFalse(unfiltered.getSql().contains("d.protocol_code ="));
    }

    @Test
    void allRecordMapsMaterializeWithJsonUuidAndNullableFields() throws Exception {
        for (String statement : List.of(
                "ConversationMapper.find",
                "ConversationMapper.listMessages",
                "RunMapper.find",
                "RunMapper.eventsAfter",
                "RunMapper.listToolCalls",
                "KnowledgeMapper.listDocuments",
                "KnowledgeMapper.candidates",
                "PermissionPolicyMapper.selectEnabled")) {
            for (boolean nullable : List.of(false, true)) {
                Object row = materialize(statement, nullable);
                assertNotNull(row, statement);
                assertTrue(row.getClass().isRecord(), statement);
                if (row instanceof Run run) {
                    assertEquals(ID, run.id());
                    assertEquals(nullable ? Map.of() : Map.of("tokens", 7), run.usage());
                    assertEquals(nullable ? List.of() : List.of("ADMIN"), run.actorRoles());
                    assertEquals(nullable ? null : NOW, run.finishedAt());
                }
                if (row instanceof Message message) {
                    assertEquals(nullable ? 0 : 1, message.citations().size());
                    if (!nullable)
                        assertEquals("doc", message.citations().getFirst().documentId());
                }
                if (row instanceof Rule rule) assertEquals(nullable ? Set.of() : Set.of("ADMIN"), rule.agentKeys());
            }
        }
    }

    private Object materialize(String name, boolean nullable) throws Exception {
        MappedStatement mapped = configuration.getMappedStatement("top.yealike.ipe.ai.mapper." + name);
        ResultMap resultMap = mapped.getResultMaps().getFirst();
        var columns = resultMap.getConstructorResultMappings();
        Map<String, Object> values = new LinkedHashMap<>();
        for (var mapping : columns) {
            Class<?> type = mapping.getJavaType();
            Object value;
            if (type == UUID.class) value = ID;
            else if (type == int.class) value = 1;
            else if (type == long.class) value = 1L;
            else if (nullable) value = null;
            else if (type == Instant.class) value = Timestamp.from(NOW);
            else if (type == Integer.class) value = 1;
            else if (type == Long.class) value = 1L;
            else if (type == Map.class) value = "{\"tokens\":7}";
            else if (mapping.getColumn().equals("citations")) value = "[{\"documentId\":\"doc\",\"title\":\"协议\"}]";
            else if (type == Set.class || type == List.class) value = "[\"ADMIN\"]";
            else value = "value";
            values.put(mapping.getColumn(), value);
        }
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(columns.size());
        int index = 1;
        for (var column : columns) {
            when(metadata.getColumnLabel(index)).thenReturn(column.getColumn());
            when(metadata.getColumnName(index)).thenReturn(column.getColumn());
            when(metadata.getColumnType(index)).thenReturn(Types.OTHER);
            when(metadata.getColumnClassName(index))
                    .thenReturn(column.getJavaType().getName());
            index++;
        }
        boolean[] wasNull = {false};
        ResultSet resultSet = mock(ResultSet.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("getMetaData")) return metadata;
            if (method.equals("getType")) return ResultSet.TYPE_FORWARD_ONLY;
            if (method.equals("wasNull")) return wasNull[0];
            if (method.startsWith("get") && invocation.getArguments().length > 0) {
                Object key = invocation.getArgument(0);
                String column = key instanceof Integer i ? columns.get(i - 1).getColumn() : (String) key;
                Object value = values.get(column);
                wasNull[0] = value == null;
                if (method.equals("getInt")) return value == null ? 0 : ((Number) value).intValue();
                if (method.equals("getLong")) return value == null ? 0L : ((Number) value).longValue();
                return value;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(resultSet.next()).thenReturn(true, false);
        Statement statement = mock(Statement.class);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(statement.getUpdateCount()).thenReturn(-1);
        var handler = new DefaultResultSetHandler(
                null,
                mapped,
                null,
                null,
                mapped.getBoundSql(Map.of("keywords", List.of("心跳"), "limit", 10)),
                RowBounds.DEFAULT);
        return handler.handleResultSets(statement).getFirst();
    }
}
