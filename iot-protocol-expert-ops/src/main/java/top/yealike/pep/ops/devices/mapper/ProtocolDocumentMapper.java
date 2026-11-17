package top.yealike.pep.ops.devices.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ProtocolDocumentMapper {

    record FileInfo(String storageKey, String originalFilename, String contentType) {}

    record Policy(String protocolNameEn, String documentVersion, String status) {}

    record Ref(Long id, String protocolCode, String protocolNameEn, String documentVersion) {}

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

    FileInfo selectFileInfo(@Param("id") long id);

    Long selectActiveIdForUpdate(@Param("id") long id);

    long countProductReferences(@Param("id") long id);

    int archive(@Param("id") long id);

    List<Map<String, Object>> selectActiveForAi();

    String selectActiveStorageKey(@Param("id") long id);

    List<Ref> selectProductRefs(@Param("productIds") Collection<Long> productIds);

    Policy selectPolicyForUpdate(@Param("id") Long id);

    Ref selectRef(@Param("id") Long id);
}
