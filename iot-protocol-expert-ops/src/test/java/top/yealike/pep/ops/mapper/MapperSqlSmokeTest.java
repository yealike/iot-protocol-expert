package top.yealike.pep.ops.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.yealike.pep.ops.access.mapper.IdentityMapper;
import top.yealike.pep.ops.access.mapper.OpsUserMapper;
import top.yealike.pep.ops.audit.mapper.AuditLogMapper;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
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
import top.yealike.pep.ops.telemetry.mapper.OrderTelemetryMapper;

@EnabledIfEnvironmentVariable(named = "IPE_OPS_DB_URL", matches = ".+")
class MapperSqlSmokeTest {

    @Test
    void allSelectStatementsExecuteAgainstPostgres() throws Exception {
        var dataSource = new DriverManagerDataSource(
                System.getenv("IPE_OPS_DB_URL"),
                System.getenv("IPE_OPS_DB_USER"),
                System.getenv("IPE_OPS_DB_PASSWORD"));
        var factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/ops/*.xml"));
        try (SqlSession session = factoryBean.getObject().openSession()) {
            LocalDateTime from = LocalDateTime.now().minusDays(1);
            LocalDateTime to = LocalDateTime.now().plusDays(1);

            ProductMapper products = session.getMapper(ProductMapper.class);
            products.selectById(-1L);
            products.selectByProductCode("__missing__");
            products.selectAllOrderByIdAsc();
            products.selectByIds(List.of(-1L));
            products.selectByProtocolAndState("OCPP16J", "PUBLISHED");
            products.search(null, null, "id DESC", 10, 0);
            products.countSearch(null, null);
            products.count();

            DeviceInstanceMapper devices = session.getMapper(DeviceInstanceMapper.class);
            devices.selectById(-1L);
            devices.selectByDeviceCode("__missing__");
            devices.selectByPlatformGunNo("__missing__");
            devices.selectAll();
            devices.search(null, null, null, null, null, null, "id DESC", 10, 0);
            devices.countSearch(null, null, null, null, null, null);
            devices.selectByProductId(-1L);
            devices.selectByIds(List.of(-1L));
            devices.selectCountByProductId(-1L);
            devices.selectNextDeviceSnCandidate();

            DeviceProtocolMappingMapper mappings = session.getMapper(DeviceProtocolMappingMapper.class);
            mappings.selectPage(0L, 10);
            mappings.selectByDeviceIds(List.of(-1L));
            mappings.selectByDeviceId(-1L);
            mappings.selectByProtocolAndThirdPartyDeviceId("OCPP16J", "__missing__");

            DeviceTagMapper tags = session.getMapper(DeviceTagMapper.class);
            tags.selectByDeviceIdOrderByTagKeyAsc(-1L);
            tags.selectByDeviceIdAndTagKey(-1L, "__missing__");
            tags.selectByDeviceIds(List.of(-1L));

            DeviceChannelMapper channels = session.getMapper(DeviceChannelMapper.class);
            channels.selectByDeviceIdOrderByChannelNoAsc(-1L);
            channels.selectByDeviceIdAndChannelNo(-1L, "__missing__");
            channels.selectByDeviceIds(List.of(-1L));
            channels.countByDeviceId(-1L);

            DevicePropertyLatestMapper latest = session.getMapper(DevicePropertyLatestMapper.class);
            latest.selectByDeviceId(-1L);
            latest.selectByDeviceAndProperty(-1L, "", "__missing__");
            latest.selectByDeviceIds(List.of(-1L));
            latest.selectCountByPropertyIdAndValueTextAndDeviceIdIn("gunState", "idle", List.of(-1L));

            DevicePropertyTsMapper series = session.getMapper(DevicePropertyTsMapper.class);
            series.selectSeries(-1L, "__missing__", from, to);

            DeviceEventLogMapper events = session.getMapper(DeviceEventLogMapper.class);
            events.selectTopByDevice(-1L, 10);
            events.selectTopByDevices(List.of(-1L), 10);

            ChargeOrderMapper orders = session.getMapper(ChargeOrderMapper.class);
            orders.selectById(-1L);
            orders.selectByOrderNo("__missing__");
            orders.selectByOrderNoForUpdate("__missing__");
            orders.selectTopActive(-1L, "__missing__", List.of(ChargeOrder.Status.PENDING));
            orders.selectTopByDevice(-1L);
            orders.selectTop200();
            orders.selectPendingBefore(LocalDateTime.now(), 10);
            orders.search(null, null, null, null, from, to, null, 10, 0);
            orders.countSearch(null, null, null, null, from, to, null);
            orders.countByStatusIn(List.of(ChargeOrder.Status.PENDING));
            orders.summarizeDevice(-1L, ChargeOrder.Status.SETTLED, from, to);
            orders.summarizeAll(ChargeOrder.Status.SETTLED, from, to);
            orders.sumEnergyActiveBetween(to, from);

            OrderExternalReferenceMapper references = session.getMapper(OrderExternalReferenceMapper.class);
            references.selectByExternal(-1L, "OCPP16J", "OCPP_TRANSACTION", "__missing__");
            references.selectByOrder(-1L, "OCPP16J", "OCPP_TRANSACTION");
            references.nextOcppTransactionId();

            ChargeOrderSegmentMapper segments = session.getMapper(ChargeOrderSegmentMapper.class);
            segments.selectByOrderId(-1L);

            BillMapper bills = session.getMapper(BillMapper.class);
            bills.selectById(-1L);
            bills.selectBySource(-1L, "OCPP16J", "OCPP_STOP_TRANSACTION", "__missing__");
            bills.search(null, null, null, from, to, null, 10);
            bills.selectByOrderId(-1L);
            bills.countByOrderId(-1L);
            bills.lockSourceTransaction(-1L, "YKC16", "YKC_TRANSACTION", "__missing__");

            RateModelMapper rates = session.getMapper(RateModelMapper.class);
            rates.selectAll();
            rates.selectByModelNo("__missing__");
            rates.selectById(-1L);
            rates.selectTopActive();

            FrameLogMapper frames = session.getMapper(FrameLogMapper.class);
            frames.search(null, null, null, null, from, to, 10);
            frames.searchForAi(-1L, "IN", null, null, from, to, 10);
            frames.selectByIdAndDeviceId(-1L, -1L);
            frames.selectRecent(10);
            frames.selectRecentByDevice(-1L, 10);
            frames.countByDirection("IN");

            CommandRecordMapper commands = session.getMapper(CommandRecordMapper.class);
            commands.selectTop(10);
            commands.selectTopByDevice(-1L, 10);

            AuditLogMapper audits = session.getMapper(AuditLogMapper.class);
            audits.selectRecent(10);

            TroubleCaseMapper cases = session.getMapper(TroubleCaseMapper.class);
            cases.selectTop(10);
            cases.selectTopByDevice(-1L, 10);

            ProtocolDocSectionMapper sections = session.getMapper(ProtocolDocSectionMapper.class);
            sections.selectByFrame("__missing__", "1", "0x3B");
            sections.search("__missing__", "1", "交易");

            ProtocolDocMapper docs = session.getMapper(ProtocolDocMapper.class);
            docs.selectByProtocolAndVersion("__missing__", "1");

            OpsUserMapper users = session.getMapper(OpsUserMapper.class);
            users.selectByUsername("__missing__");

            IdentityMapper identities = session.getMapper(IdentityMapper.class);
            identities.selectAllUserIds();
            identities.selectIdentityById(-1L);
            identities.selectIdentityByUsername("__missing__");
            identities.selectRoleCodes(-1L);
            identities.selectPermissions(-1L);
            identities.selectRoles();
            identities.selectRolePermissions("__missing__");
            identities.countUsername("__missing__");

            ProtocolDocumentMapper documents = session.getMapper(ProtocolDocumentMapper.class);
            documents.selectActive("");
            documents.selectActiveForAi();
            documents.selectActiveStorageKey(-1L);
            documents.selectProductRefs(List.of(-1L));
            documents.selectRef(-1L);
            documents.selectFileInfo(-1L);
            documents.countProductReferences(-1L);

            OrderTelemetryMapper telemetry = session.getMapper(OrderTelemetryMapper.class);
            telemetry.existsPending("__missing__");
            assertNotNull(telemetry.tryAdvisoryLock());
            telemetry.selectPendingSamples(1);
        }
    }
}
