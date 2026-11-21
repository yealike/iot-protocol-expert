package top.yealike.pep.ops.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.yealike.pep.ops.access.domain.OpsUser;
import top.yealike.pep.ops.access.mapper.IdentityMapper;
import top.yealike.pep.ops.access.mapper.OpsUserMapper;
import top.yealike.pep.ops.audit.AuditLog;
import top.yealike.pep.ops.audit.mapper.AuditLogMapper;
import top.yealike.pep.ops.charging.domain.Bill;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.domain.ChargeOrderSegment;
import top.yealike.pep.ops.charging.domain.CommandRecord;
import top.yealike.pep.ops.charging.domain.OrderExternalReference;
import top.yealike.pep.ops.charging.domain.RateModel;
import top.yealike.pep.ops.charging.mapper.BillMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderMapper;
import top.yealike.pep.ops.charging.mapper.ChargeOrderSegmentMapper;
import top.yealike.pep.ops.charging.mapper.CommandRecordMapper;
import top.yealike.pep.ops.charging.mapper.OrderExternalReferenceMapper;
import top.yealike.pep.ops.charging.mapper.RateModelMapper;
import top.yealike.pep.ops.devices.domain.FrameLog;
import top.yealike.pep.ops.devices.entity.DeviceChannel;
import top.yealike.pep.ops.devices.entity.DeviceEventLog;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.entity.DevicePropertyLatest;
import top.yealike.pep.ops.devices.entity.DevicePropertyTs;
import top.yealike.pep.ops.devices.entity.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.entity.DeviceTag;
import top.yealike.pep.ops.devices.entity.Product;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceEventLogMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyLatestMapper;
import top.yealike.pep.ops.devices.mapper.DevicePropertyTsMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.mapper.DeviceTagMapper;
import top.yealike.pep.ops.devices.mapper.FrameLogMapper;
import top.yealike.pep.ops.devices.mapper.ProductMapper;
import top.yealike.pep.ops.devices.mapper.ProtocolDocumentMapper;
import top.yealike.pep.ops.diagnostic.TroubleCase;
import top.yealike.pep.ops.diagnostic.mapper.TroubleCaseMapper;
import top.yealike.pep.ops.telemetry.mapper.OrderTelemetryMapper;

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
        String orderNo = String.format("%021d", Math.abs(System.nanoTime()));
        String rateModelNo = String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        LocalDateTime now = LocalDateTime.now();
        try (SqlSession session = factoryBean.getObject().openSession()) {
            try {
                ProductMapper products = session.getMapper(ProductMapper.class);
                Product product = new Product();
                product.productCode = "T-" + suffix;
                product.name = "test";
                product.categoryCode = "charging";
                product.protocol = "YKC16";
                product.transport = "TCP";
                product.metadata = "{}";
                product.connectorCount = 2;
                product.state = "DRAFT";
                product.createdAt = now;
                product.updatedAt = now;
                assertEquals(1, products.insert(product));
                assertNotNull(product.id);
                product.name = "test-updated";
                assertEquals(1, products.update(product));

                DeviceInstanceMapper devices = session.getMapper(DeviceInstanceMapper.class);
                DeviceInstance device = new DeviceInstance();
                device.deviceCode = devices.selectNextDeviceSnCandidate();
                device.name = "test";
                device.productId = product.id;
                device.protocol = product.protocol;
                device.state = DeviceInstance.STATE_OFFLINE;
                device.configuration = "{}";
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
                channel.platformGunNo = device.deviceCode + channel.channelNo;
                channel.protocolConnectorNo = "1";
                channel.name = "gun";
                channel.createdAt = now;
                channel.updatedAt = now;
                assertEquals(1, channels.insert(channel));
                assertNotNull(channel.id);
                channel.name = "gun-updated";
                assertEquals(1, channels.update(channel));

                DeviceProtocolMappingMapper protocolMappings = session.getMapper(DeviceProtocolMappingMapper.class);
                DeviceProtocolMapping mapping = new DeviceProtocolMapping();
                mapping.deviceId = device.id;
                mapping.protocol = "YKC16";
                mapping.thirdPartyDeviceId = "T-" + suffix;
                mapping.enabled = true;
                mapping.createdAt = now;
                mapping.updatedAt = now;
                assertEquals(1, protocolMappings.insert(mapping));
                assertNotNull(mapping.id);
                mapping.thirdPartyDeviceId = "T-" + suffix + "-updated";
                mapping.updatedAt = now.plusSeconds(1);
                assertEquals(1, protocolMappings.update(mapping));

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

                ChargeOrderMapper orders = session.getMapper(ChargeOrderMapper.class);
                ChargeOrder order = new ChargeOrder();
                order.orderNo = orderNo;
                order.deviceId = device.id;
                order.deviceSn = device.deviceCode;
                order.gunNo = "01";
                order.platformGunNo = channel.platformGunNo;
                order.protocol = "YKC16";
                order.electricityFee = new BigDecimal("1.2000");
                order.serviceFee = new BigDecimal("0.4000");
                order.amount = new BigDecimal("1.6000");
                order.lossEnergy = BigDecimal.ZERO;
                order.rateModelNo = rateModelNo;
                order.rateSnapshot = "{}";
                order.feeBreakdown = "[]";
                order.status = ChargeOrder.Status.PENDING;
                order.createdAt = now;
                order.updatedAt = now;
                assertEquals(1, orders.insert(order));
                assertNotNull(order.id);
                order.status = ChargeOrder.Status.CHARGING;
                assertEquals(1, orders.update(order));

                OrderExternalReferenceMapper references = session.getMapper(OrderExternalReferenceMapper.class);
                OrderExternalReference reference = new OrderExternalReference();
                reference.orderId = order.id;
                reference.deviceId = device.id;
                reference.protocol = order.protocol;
                reference.referenceType = "YKC_TRANSACTION";
                reference.externalReference = suffix;
                reference.createdAt = now;
                assertEquals(1, references.insert(reference));
                assertNotNull(reference.id);
                assertNotNull(references.selectByExternal(device.id, order.protocol, "YKC_TRANSACTION", suffix));
                assertNotNull(references.selectByOrder(order.id, order.protocol, "YKC_TRANSACTION"));
                assertNotNull(references.nextOcppTransactionId());

                ChargeOrderSegmentMapper segments = session.getMapper(ChargeOrderSegmentMapper.class);
                ChargeOrderSegment segment = new ChargeOrderSegment();
                segment.orderId = order.id;
                segment.period = 0;
                segment.energy = new BigDecimal("1.0000");
                segment.electricityPrice = new BigDecimal("1.20000");
                segment.servicePrice = new BigDecimal("0.40000");
                segment.electricityFee = new BigDecimal("1.2000");
                segment.serviceFee = new BigDecimal("0.4000");
                segment.totalAmount = new BigDecimal("1.6000");
                segment.source = ChargeOrder.AMOUNT_SOURCE_DEVICE;
                segment.createdAt = now;
                assertEquals(1, segments.insertBatch(List.of(segment)));
                assertEquals(1, segments.selectByOrderId(order.id).size());

                BillMapper bills = session.getMapper(BillMapper.class);
                Bill bill = new Bill();
                bill.orderId = order.id;
                bill.orderNo = order.orderNo;
                bill.deviceId = device.id;
                bill.protocol = "YKC16";
                bill.sourceType = "TEST";
                bill.sourceTransactionNo = suffix;
                bill.electricityFee = new BigDecimal("1.2000");
                bill.serviceFee = new BigDecimal("0.4000");
                bill.amount = new BigDecimal("1.6000");
                bill.lossEnergy = BigDecimal.ZERO;
                bill.rateModelNo = rateModelNo;
                bill.rateSnapshot = "{}";
                bill.feeBreakdown = "[]";
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
                rate.modelNo = rateModelNo;
                rate.segments = "2,2,2,2";
                rate.updatedAt = now;
                assertEquals(1, rates.insert(rate));
                assertNotNull(rate.id);
                rate.lossRatio = 1;
                assertEquals(1, rates.update(rate));

                FrameLogMapper frames = session.getMapper(FrameLogMapper.class);
                FrameLog frame = new FrameLog();
                frame.deviceId = device.id;
                frame.protocol = "YKC16";
                frame.protocolDeviceId = mapping.thirdPartyDeviceId;
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
                assertEquals(1, identities.insertUser(managed));
                assertNotNull(managed.id);
                assertEquals(1, identities.insertMemberships(managed.id));
                assertEquals(1, identities.updateUserAccess(managed.id, false));
                assertEquals(1, identities.updatePassword(managed.id, "hash2"));
                identities.deleteUserRoles(managed.id);
                String testRole = "T" + suffix;
                try (var statement = session.getConnection()
                        .prepareStatement("insert into pep_platform.role(id, code, name, scope_type, built_in) "
                                + "values (gen_random_uuid(), ?, ?, 'PROJECT', false)")) {
                    statement.setString(1, testRole);
                    statement.setString(2, "test");
                    assertEquals(1, statement.executeUpdate());
                }
                identities.insertUserRole(managed.id, testRole);
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
                assertEquals(1, segments.deleteByOrderId(order.id));
            } finally {
                session.rollback();
            }
        }
    }
}
