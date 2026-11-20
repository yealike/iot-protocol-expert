package top.yealike.pep.control.agents.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
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
import top.yealike.pep.control.access.policy.PolicyService.Rule;
import top.yealike.pep.control.conversations.ConversationRecords.Message;
import top.yealike.pep.control.conversations.mapper.ConversationMapper;
import top.yealike.pep.control.knowledge.mapper.KnowledgeMapper;
import top.yealike.pep.control.mybatis.McpToolExecutionCategoryTypeHandler;
import top.yealike.pep.control.mybatis.UuidTypeHandler;
import top.yealike.pep.control.runs.RunRecords.Run;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.McpToolDescriptor;
import top.yealike.pep.runtime.contract.McpToolExecutionCategory;
import top.yealike.pep.runtime.contract.SkillBundle;

class MapperXmlBindingTest {
    private static Configuration configuration;
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final List<Class<?>> MAPPERS = List.of(
            AgentPlatformMapper.class,
            AiAuditLogMapper.class,
            ConversationMapper.class,
            CredentialMapper.class,
            InternalControlMapper.class,
            KnowledgeMapper.class,
            McpPlatformMapper.class,
            ModelProfileMapper.class,
            PermissionPolicyMapper.class,
            PublicationMapper.class,
            RegistryProjectionMapper.class,
            RunMapper.class,
            SkillPlatformMapper.class);

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
                    assertFalse(
                            sql.getSql().matches("(?s).*\\?\\s+is\\s+null.*"),
                            "null-only parameter comparison: " + statement.getId());
                    ParameterHandler handler = configuration.newParameterHandler(statement, params, sql);
                    handler.setParameters(mock(PreparedStatement.class));
                }
            }
        }
    }

    @Test
    void optionalConversationFilterIsOmittedWhenNull() {
        for (String id : List.of("countUserQueued", "countUserActive")) {
            var statement = configuration.getMappedStatement(RunMapper.class.getName() + "." + id);
            Map<String, Object> withoutConversation = new HashMap<>();
            withoutConversation.put("actor", "user");
            withoutConversation.put("excludeConversationId", null);
            BoundSql unfiltered = statement.getBoundSql(withoutConversation);
            assertFalse(unfiltered.getSql().contains("conversation_id !="), id);
            assertEquals(1, unfiltered.getParameterMappings().size(), id);

            Map<String, Object> withConversation = new HashMap<>(withoutConversation);
            withConversation.put("excludeConversationId", ID);
            BoundSql filtered = statement.getBoundSql(withConversation);
            assertTrue(filtered.getSql().contains("conversation_id !="), id);
            assertEquals(2, filtered.getParameterMappings().size(), id);
        }
    }

    private static Object sample(Class<?> type, boolean nullable) {
        if (type == boolean.class) return true;
        if (type == int.class) return 10;
        if (type == long.class) return 1L;
        if (type == byte[].class) return new byte[] {1};
        if (type == List.class) return List.of();
        if (nullable) return null;
        if (type == Boolean.class) return true;
        if (type == Integer.class) return 10;
        if (type == Long.class) return 1L;
        if (type == BigDecimal.class) return BigDecimal.ONE;
        if (type == UUID.class) return ID;
        if (type == Instant.class) return NOW;
        return "value";
    }

    @Test
    void atomicCountersAreDirtySelectsAndNeverCached() {
        for (String id : List.of(
                ConversationMapper.class.getName() + ".nextMessageSeq",
                RunMapper.class.getName() + ".nextEventCursor")) {
            var statement = configuration.getMappedStatement(id);
            assertTrue(statement.isDirtySelect());
            assertTrue(statement.isFlushCacheRequired());
            assertFalse(statement.isUseCache());
            assertTrue(
                    statement.getBoundSql(Map.of("conversationId", ID)).getSql().contains("returning"));
        }
    }

    @Test
    void mcpToolCategoryHandlerPreservesLegacyTolerance() throws Exception {
        var handler = new McpToolExecutionCategoryTypeHandler();
        ResultSet rows = mock(ResultSet.class);
        when(rows.getString("execution_category")).thenReturn(" read_only ");
        assertEquals(McpToolExecutionCategory.READ_ONLY, handler.getNullableResult(rows, "execution_category"));
        when(rows.getString("execution_category")).thenReturn("legacy-value");
        assertEquals(McpToolExecutionCategory.UNKNOWN, handler.getNullableResult(rows, "execution_category"));
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
    void controlExpiryTerminationUsesExpiredCasInsteadOfActiveLease() {
        var statement = configuration.getMappedStatement(RunMapper.class.getName() + ".terminateExpired");
        String sql = statement.getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("lease_until < now()"));
        assertTrue(sql.contains("deadline_at < now()"));
        assertFalse(sql.contains("lease_until > now()"));
        assertTrue(sql.contains("returning status"));

        var runtimeFailure = configuration.getMappedStatement(RunMapper.class.getName() + ".markFailedWithToken");
        String runtimeSql = runtimeFailure.getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ");
        assertTrue(runtimeSql.contains("lease_until > now()"));
        assertTrue(runtimeSql.contains("deadline_at > now()"));

        var expired = configuration.getMappedStatement(RunMapper.class.getName() + ".selectExpiredLeases");
        String expiredSql = expired.getBoundSql(Map.of("limit", 20)).getSql().replaceAll("\\s+", " ");
        assertTrue(expiredSql.contains("deadline_at < now()"));
    }

    @Test
    void dispatchQuotaSqlSeparatesQueuedAndActiveRuns() {
        var next = configuration.getMappedStatement(RunMapper.class.getName() + ".selectNextQueued");
        String nextSql =
                next.getBoundSql(Map.of("perUserActiveLimit", 1)).getSql().replaceAll("\\s+", " ");
        assertTrue(nextSql.contains("a.status in ('DISPATCHING', 'RUNNING', 'CANCEL_REQUESTED')"));
        assertEquals(
                1,
                next.getBoundSql(Map.of("perUserActiveLimit", 1))
                        .getParameterMappings()
                        .size());

        var active = configuration.getMappedStatement(RunMapper.class.getName() + ".countUserActive");
        String activeSql = active.getBoundSql(Map.of("actor", "user", "excludeConversationId", ID))
                .getSql()
                .replaceAll("\\s+", " ");
        assertFalse(activeSql.contains("'QUEUED'"));
        assertTrue(activeSql.contains("'DISPATCHING'"));
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
                "PermissionPolicyMapper.selectEnabled",
                "AgentPlatformMapper.find",
                "AgentPlatformMapper.mcpBindings",
                "AgentPlatformMapper.skillBindings",
                "AgentPlatformMapper.findReleaseById",
                "McpPlatformMapper.findComponent",
                "McpPlatformMapper.findRelease",
                "McpPlatformMapper.findTestReport",
                "McpPlatformMapper.latestTestReport",
                "McpPlatformMapper.toolPolicies",
                "SkillPlatformMapper.findComponent",
                "SkillPlatformMapper.files",
                "SkillPlatformMapper.findRelease",
                "CredentialMapper.find",
                "CredentialMapper.findSecret",
                "ModelProfileMapper.find",
                "PublicationMapper.find",
                "PublicationMapper.claimedByWorker",
                "PublicationMapper.claimedOutbox",
                "RegistryProjectionMapper.list")) {
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
        String statementId;
        if (name.startsWith("ConversationMapper.")) {
            statementId = ConversationMapper.class.getName() + name.substring("ConversationMapper".length());
        } else if (name.startsWith("KnowledgeMapper.")) {
            statementId = KnowledgeMapper.class.getName() + name.substring("KnowledgeMapper".length());
        } else {
            statementId = "top.yealike.pep.control.agents.mapper." + name;
        }
        MappedStatement mapped = configuration.getMappedStatement(statementId);
        ResultMap resultMap = mapped.getResultMaps().getFirst();
        var columns = resultMap.getConstructorResultMappings();
        Map<String, Object> values = new LinkedHashMap<>();
        for (var mapping : columns) {
            Class<?> type = mapping.getJavaType();
            Object value;
            if (type == UUID.class) value = ID;
            else if (type == boolean.class) value = true;
            else if (type == int.class) value = 1;
            else if (type == long.class) value = 1L;
            else if (type == byte[].class) value = new byte[] {1};
            else if (nullable) value = null;
            else if (type == Instant.class) value = Timestamp.from(NOW);
            else if (type == Boolean.class) value = true;
            else if (type == Integer.class) value = 1;
            else if (type == Long.class) value = 1L;
            else if (type == BigDecimal.class) value = BigDecimal.ONE;
            else if (type == Map.class) value = "{\"tokens\":7}";
            else if (type == AgentManifest.class) {
                value = "{\"agentKey\":\"agent\",\"version\":\"1.0.0\",\"skills\":[],\"mcp\":[]}";
            } else if (type == SkillBundle.class) {
                value = "{\"key\":\"skill\",\"version\":\"1.0.0\",\"name\":\"Skill\",\"files\":[]}";
            } else if (type == McpToolDescriptor.class) {
                value =
                        "{\"name\":\"tool\",\"inputSchema\":{},\"outputSchema\":{},\"annotations\":{},\"readOnly\":true}";
            } else if (type == McpToolExecutionCategory.class) value = "READ_ONLY";
            else if (mapping.getColumn().equals("citations")) value = "[{\"documentId\":\"doc\",\"title\":\"协议\"}]";
            else if (mapping.getColumn().equals("tool_catalog")) {
                value =
                        "[{\"name\":\"tool\",\"inputSchema\":{},\"outputSchema\":{},\"annotations\":{},\"readOnly\":true}]";
            } else if (type == Set.class || type == List.class) value = "[\"ADMIN\"]";
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
