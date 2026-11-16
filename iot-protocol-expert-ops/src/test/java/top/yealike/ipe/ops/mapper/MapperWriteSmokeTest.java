package top.yealike.ipe.ops.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.yealike.ipe.ops.alarm.entity.AlarmRecord;
import top.yealike.ipe.ops.alarm.entity.AlarmRule;
import top.yealike.ipe.ops.device.entity.DeviceChannel;
import top.yealike.ipe.ops.device.entity.DeviceEventLog;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.entity.DevicePropertyLatest;
import top.yealike.ipe.ops.device.entity.DevicePropertyTs;
import top.yealike.ipe.ops.device.entity.DeviceTag;
import top.yealike.ipe.ops.device.entity.Product;
import top.yealike.ipe.ops.domain.AuditLog;
import top.yealike.ipe.ops.domain.Bill;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.domain.CommandRecord;
import top.yealike.ipe.ops.domain.FrameLog;
import top.yealike.ipe.ops.domain.OpsUser;
import top.yealike.ipe.ops.domain.RateModel;
import top.yealike.ipe.ops.domain.Station;
import top.yealike.ipe.ops.domain.TroubleCase;

@EnabledIfEnvironmentVariable(named = "IPE_OPS_DB_URL", matches = ".+")
class MapperWriteSmokeTest {

    @Test
    void allWriteStatementsExecuteAndGeneratedKeysAreReturned() throws Exception {
        var dataSource = new DriverManagerDataSource(
                System.getenv("IPE_OPS_DB_URL"),
                System.getenv("IPE_OPS_DB_USER"),
                System.getenv("IPE_OPS_DB_PASSWORD"));
        var factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionFactory(new JdbcTransactionFactory());
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/ops/*.xml"));
        String suffix = Long.toString(System.nanoTime());
        LocalDateTime now = LocalDateTime.now();
        try (SqlSession session = factoryBean.getObject().openSession()) {
            try {
                StationMapper stations = session.getMapper(StationMapper.class);
                Station station = new Station();
                station.stationCode = "T-" + suffix;
                station.name = "test";
                station.timezone = "Asia/Shanghai";
                station.status = "ENABLED";
                station.createdAt = now;
                station.updatedAt = now;
                assertEquals(1, stations.insert(station));
                assertNotNull(station.id);
                station.name = "test-updated";
                assertEquals(1, stations.update(station));

                ProductMapper products = session.getMapper(ProductMapper.class);
                Product product = new Product();
                product.productCode = "T-" + suffix;
                product.name = "test";
                product.categoryCode = "charging";
                product.deviceType = "CHARGER";
                product.protocol = "YKC16";
                product.transport = "TCP";
                product.metadata = "{}";
                product.state = "DRAFT";
                product.createdAt = now;
                product.updatedAt = now;
                assertEquals(1, products.insert(product));
                assertNotNull(product.id);
                product.name = "test-updated";
                assertEquals(1, products.update(product));

                DeviceInstanceMapper devices = session.getMapper(DeviceInstanceMapper.class);
                DeviceInstance device = new DeviceInstance();
                device.deviceCode = "T-" + suffix;
                device.name = "test";
                device.productId = product.id;
                device.deviceType = product.deviceType;
                device.protocol = product.protocol;
                device.protocolDeviceId = "T-" + suffix;
                device.stationId = station.id;
                device.state = DeviceInstance.STATE_OFFLINE;
                device.createdAt = now;
                device.updatedAt = now;
                assertEquals(1, devices.insert(device));
                assertNotNull(device.id);
                device.name = "test-updated";
                assertEquals(1, devices.update(device));

                DeviceTagMapper tags = session.getMapper(DeviceTagMapper.class);
                DeviceTag tag = new DeviceTag(device.id, "testKey", "testValue", "string");
                assertEquals(1, tags.insert(tag));
                assertNotNull(tag.id);
                tag.tagValue = "updated";
                assertEquals(1, tags.update(tag));

                DeviceChannelMapper channels = session.getMapper(DeviceChannelMapper.class);
                DeviceChannel channel = new DeviceChannel();
                channel.deviceId = device.id;
                channel.channelNo = "01";
                channel.channelType = "CONNECTOR";
                channel.name = "gun";
                channel.createdAt = now;
                channel.updatedAt = now;
                assertEquals(1, channels.insert(channel));
                assertNotNull(channel.id);
                channel.name = "gun-updated";
                assertEquals(1, channels.update(channel));

                DevicePropertyLatestMapper latest = session.getMapper(DevicePropertyLatestMapper.class);
                DevicePropertyLatest row = new DevicePropertyLatest(device.id, "", "soc", "int");
                row.valueNum = BigDecimal.TEN;
                row.updatedAt = now;
                assertEquals(1, latest.insert(row));
                assertNotNull(row.id);
                row.valueNum = BigDecimal.ONE;
                assertEquals(1, latest.update(row));

                DevicePropertyTsMapper series = session.getMapper(DevicePropertyTsMapper.class);
                DevicePropertyTs point = new DevicePropertyTs(device.id, "", "soc", BigDecimal.ONE, null, null, now);
                assertEquals(1, series.insert(point));
                assertNotNull(point.id);

                DeviceEventLogMapper events = session.getMapper(DeviceEventLogMapper.class);
                DeviceEventLog event = new DeviceEventLog(device.id, "", "testEvent", "INFO", "{}", now);
                assertEquals(1, events.insert(event));
                assertNotNull(event.id);

                AlarmRuleMapper rules = session.getMapper(AlarmRuleMapper.class);
                AlarmRule rule = new AlarmRule();
                rule.ruleCode = "T-" + suffix;
                rule.name = "test";
                rule.productId = product.id;
                rule.level = AlarmRule.LEVEL_WARN;
                rule.metricProperty = "soc";
                rule.operator = "lt";
                rule.threshold = BigDecimal.ONE;
                rule.createdAt = now;
                rule.updatedAt = now;
                assertEquals(1, rules.insert(rule));
                assertNotNull(rule.id);
                rule.name = "test-updated";
                assertEquals(1, rules.update(rule));

                AlarmRecordMapper records = session.getMapper(AlarmRecordMapper.class);
                AlarmRecord record = new AlarmRecord();
                record.ruleId = rule.id;
                record.deviceId = device.id;
                record.channelNo = "";
                record.level = AlarmRule.LEVEL_WARN;
                record.title = "test";
                record.detail = "{}";
                record.state = AlarmRecord.STATE_OPEN;
                record.firstAt = now;
                record.lastAt = now;
                record.createdAt = now;
                assertEquals(1, records.insert(record));
                assertNotNull(record.id);
                record.state = AlarmRecord.STATE_ACK;
                assertEquals(1, records.update(record));

                ChargeOrderMapper orders = session.getMapper(ChargeOrderMapper.class);
                ChargeOrder order = new ChargeOrder();
                order.orderNo = "T-" + suffix;
                order.deviceId = device.id;
                order.gunNo = "01";
                order.stationId = station.id;
                order.protocol = "YKC16";
                order.status = ChargeOrder.Status.PENDING;
                order.createdAt = now;
                order.updatedAt = now;
                assertEquals(1, orders.insert(order));
                assertNotNull(order.id);
                order.status = ChargeOrder.Status.CHARGING;
                assertEquals(1, orders.update(order));

                BillMapper bills = session.getMapper(BillMapper.class);
                Bill bill = new Bill();
                bill.orderId = order.id;
                bill.orderNo = order.orderNo;
                bill.deviceId = device.id;
                bill.stationId = station.id;
                bill.protocol = "YKC16";
                bill.sourceType = "TEST";
                bill.sourceTransactionNo = suffix;
                bill.amountType = ChargeOrder.AMOUNT_SOURCE_DEVICE;
                bill.endedAt = now;
                bill.receivedAt = now;
                bill.createdAt = now;
                assertEquals(1, bills.insert(bill));
                assertNotNull(bill.id);
                bill.rejectReason = "test";
                assertEquals(1, bills.update(bill));

                RateModelMapper rates = session.getMapper(RateModelMapper.class);
                RateModel rate = new RateModel();
                rate.modelNo = suffix.substring(Math.max(0, suffix.length() - 8));
                rate.segments = "2,2,2,2";
                rate.updatedAt = now;
                assertEquals(1, rates.insert(rate));
                assertNotNull(rate.id);
                rate.active = true;
                assertEquals(1, rates.update(rate));

                FrameLogMapper frames = session.getMapper(FrameLogMapper.class);
                FrameLog frame = new FrameLog();
                frame.deviceId = device.id;
                frame.protocol = "YKC16";
                frame.protocolDeviceId = device.protocolDeviceId;
                frame.direction = "IN";
                frame.createdAt = now;
                assertEquals(1, frames.insert(frame));
                assertNotNull(frame.id);

                CommandRecordMapper commands = session.getMapper(CommandRecordMapper.class);
                CommandRecord command = new CommandRecord();
                command.deviceId = device.id;
                command.protocol = "YKC16";
                command.commandType = "test";
                command.status = CommandRecord.Status.PENDING;
                command.createdAt = now;
                command.updatedAt = now;
                assertEquals(1, commands.insert(command));
                assertNotNull(command.id);
                command.status = CommandRecord.Status.SENT;
                assertEquals(1, commands.update(command));

                AuditLogMapper audits = session.getMapper(AuditLogMapper.class);
                AuditLog audit = new AuditLog();
                audit.actor = "test";
                audit.action = "TEST";
                audit.createdAt = now;
                assertEquals(1, audits.insert(audit));
                assertNotNull(audit.id);

                TroubleCaseMapper cases = session.getMapper(TroubleCaseMapper.class);
                TroubleCase troubleCase = new TroubleCase();
                troubleCase.deviceId = device.id;
                troubleCase.createdAt = now;
                assertEquals(1, cases.insert(troubleCase));
                assertNotNull(troubleCase.id);

                OpsUserMapper users = session.getMapper(OpsUserMapper.class);
                OpsUser user = new OpsUser();
                user.username = "t-" + suffix;
                user.passwordHash = "hash";
                user.createdAt = now;
                assertEquals(1, users.insert(user));
                assertNotNull(user.id);

                IdentityMapper identities = session.getMapper(IdentityMapper.class);
                OpsUser managed = new OpsUser();
                managed.username = "m-" + suffix;
                managed.passwordHash = "hash";
                managed.createdAt = now;
                assertEquals(1, identities.insertUser(managed, false));
                assertNotNull(managed.id);
                assertEquals(1, identities.updateUserAccess(managed.id, false, true));
                assertEquals(1, identities.updatePassword(managed.id, "hash2"));
                identities.deleteUserRoles(managed.id);
                identities.deleteUserStations(managed.id);
                String testRole = "T" + suffix;
                try (var statement = session.getConnection()
                        .prepareStatement("insert into ipe_ops.iam_role(code, name) values (?, ?)")) {
                    statement.setString(1, testRole);
                    statement.setString(2, "test");
                    assertEquals(1, statement.executeUpdate());
                }
                identities.insertUserRole(managed.id, testRole);
                identities.insertUserStation(managed.id, station.id);
                identities.deleteRolePermissions(testRole);
                identities.insertRolePermission(testRole, "iam:user:manage");
                assertEquals(1, identities.incrementAuthVersionByRole(testRole));

                ProtocolDocumentMapper documents = session.getMapper(ProtocolDocumentMapper.class);
                Long documentId = documents.insertDocument(
                        "T",
                        "T",
                        "1.0",
                        "test",
                        "test.md",
                        "12345678-1234-1234-1234-123456789abc",
                        "text/plain",
                        1L,
                        "0".repeat(64),
                        "test");
                assertNotNull(documentId);
                assertNotNull(documents.selectFileInfo(documentId));
                assertNotNull(documents.selectRef(documentId));
                assertEquals(0, documents.countProductReferences(documentId));
                assertNotNull(documents.selectActiveIdForUpdate(documentId));
                assertEquals(1, documents.archive(documentId));

                OrderTelemetryMapper telemetry = session.getMapper(OrderTelemetryMapper.class);
                assertEquals(
                        1,
                        telemetry.insertSample(
                                order.orderNo,
                                java.sql.Timestamp.from(now.atZone(java.time.ZoneId.systemDefault())
                                        .toInstant()),
                                "DEVICE",
                                BigDecimal.ONE,
                                null,
                                null,
                                null,
                                null,
                                null));
                assertTrue(telemetry.existsPending(order.orderNo));
                assertFalse(telemetry.lockPendingSamples().isEmpty());
                telemetry.deleteOutboxById(
                        telemetry.lockPendingSamples().getFirst().id());

                identities.deleteUserRoles(managed.id);
                identities.deleteUserStations(managed.id);
                assertEquals(1, rules.deleteById(rule.id));
                assertEquals(1, stations.deleteById(station.id));
            } finally {
                session.rollback();
            }
        }
    }
}
