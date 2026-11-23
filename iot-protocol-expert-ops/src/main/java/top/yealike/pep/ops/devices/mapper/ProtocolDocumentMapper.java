package top.yealike.pep.ops.devices.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.devices.domain.ProtocolDocumentFileInfo;
import top.yealike.pep.ops.devices.domain.ProtocolDocumentPolicy;
import top.yealike.pep.ops.devices.domain.ProtocolDocumentRef;

public interface ProtocolDocumentMapper {

    List<Map<String, Object>> selectActive(@Param("protocol") String protocol);

    Long insertDocument(
            @Param("protocolCode") String protocolCode,
            @Param("protocolNameEn") String protocolNameEn,
            @Param("documentVersion") String documentVersion,
            @Param("title") String title,
            @Param("originalFilename") String originalFilename,
            @Param("storageKey") String storageKey,
            @Param("contentType") String contentType,
            @Param("fileSize") long fileSize,
            @Param("sha256") String sha256,
            @Param("uploadedBy") String uploadedBy);

    int linkProductsByProtocolIdentity(
            @Param("id") long id,
            @Param("protocolNameEn") String protocolNameEn,
            @Param("documentVersion") String documentVersion);

    ProtocolDocumentFileInfo selectFileInfo(@Param("id") long id);

    Long selectActiveIdForUpdate(@Param("id") long id);

    long countProductReferences(@Param("id") long id);

    int archive(@Param("id") long id);

    List<Map<String, Object>> selectActiveForAi();

    String selectActiveStorageKey(@Param("id") long id);

    List<ProtocolDocumentRef> selectProductRefs(@Param("productIds") Collection<Long> productIds);

    ProtocolDocumentPolicy selectPolicyForUpdate(@Param("id") Long id);

    ProtocolDocumentRef selectRef(@Param("id") Long id);
}
