package top.yealike.pep.control.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.yealike.pep.control.charging.domain.ChargeOrder;

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

            StationMapper stations = session.getMapper(StationMapper.class);
            stations.selectById(-1L);
            stations.selectByStationCode("__missing__");
            stations.selectAll();
            stations.selectByIds(List.of(-1L));
            stations.count();
            stations.countById(-1L);

            ProductMapper products = session.getMapper(ProductMapper.class);
            products.selectById(-1L);
            products.selectByProductCode("__missing__");
            products.selectAllOrderByIdAsc();
            products.search(null, null, null, "id DESC", 10, 0);
            products.countSearch(null, null, null);
            products.count();

            DeviceInstanceMapper devices = session.getMapper(DeviceInstanceMapper.class);
            devices.selectById(-1L);
            devices.selectByDeviceCode("__missing__");
            devices.selectByProtocolAndProtocolDeviceId("YKC16", "__missing__");
            devices.selectAll();
            devices.search(null, null, null, null, null, null, Map.of(), "id DESC", 10, 0);
            devices.countSearch(null, null, null, null, null, null, Map.of());
            devices.selectByDeviceType("__missing__");
            devices.selectByStationId(-1L);
            devices.selectByProductId(-1L);
            devices.selectByIds(List.of(-1L));

            DeviceTagMapper tags = session.getMapper(DeviceTagMapper.class);
            tags.selectByDeviceIdOrderByTagKeyAsc(-1L);
            tags.selectByDeviceIdAndTagKey(-1L, "__missing__");
            tags.selectByDeviceIds(List.of(-1L));

            DeviceChannelMapper channels = session.getMapper(DeviceChannelMapper.class);
            channels.selectByDeviceIdOrderByChannelNoAsc(-1L);
            channels.selectByDeviceIdAndChannelNo(-1L, "__missing__");
            channels.selectByDeviceIds(List.of(-1L));
            channels.countByDeviceId(-1L);
            channels.countByDeviceIdAndChannelType(-1L, "CONNECTOR");

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

            AlarmRuleMapper rules = session.getMapper(AlarmRuleMapper.class);
            rules.selectAll();
            rules.selectByRuleCode("__missing__");
            rules.selectByProductId(-1L);
            rules.selectEnabledByProductId(-1L);
            rules.selectEnabledByDeviceId(-1L);

            AlarmRecordMapper records = session.getMapper(AlarmRecordMapper.class);
            records.selectById(-1L);
            records.search(null, null, null, null, null, null, "last_at DESC", 10, 0);
            records.countSearch(null, null, null, null, null, null);
            records.selectByDeviceIdAndState(-1L, "OPEN");
            records.countByState("OPEN");
            records.countByStateAndLevel("OPEN", "WARN");

            ChargeOrderMapper orders = session.getMapper(ChargeOrderMapper.class);
            orders.selectById(-1L);
            orders.selectByOrderNo("__missing__");
            orders.selectTop200();
            orders.search(null, null, null, null, null, from, to, null, 10, 0);
            orders.countSearch(null, null, null, null, null, from, to, null);
            orders.maxOcppTransactionId();
            orders.countByStatusIn(List.of(ChargeOrder.Status.PENDING));
            orders.summarizeStation(-1L, ChargeOrder.Status.SETTLED, from, to);
            orders.summarizeDevice(-1L, ChargeOrder.Status.SETTLED, from, to);
            orders.summarizeAll(ChargeOrder.Status.SETTLED, from, to);
            orders.sumEnergyActiveBetween(to, from);

            BillMapper bills = session.getMapper(BillMapper.class);
            bills.selectById(-1L);
            bills.search(null, null, null, null, from, to, null, 10);
            bills.selectByOrderId(-1L);
            bills.countByOrderId(-1L);
            bills.summarizeStation(-1L, "DEVICE_BILL", from, to);
            bills.countByStationIdIsNull();

            RateModelMapper rates = session.getMapper(RateModelMapper.class);
            rates.selectAll();
            rates.selectByModelNo("__missing__");
            rates.selectById(-1L);

            FrameLogMapper frames = session.getMapper(FrameLogMapper.class);
            frames.search(null, null, null, null, from, to, 10);
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
            identities.selectStationIds(-1L);
            identities.selectRoles();
            identities.selectRolePermissions("__missing__");
            identities.countUsername("__missing__");
            identities.stationExists(-1L);

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
