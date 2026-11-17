package top.yealike.ipe.ai.mapper;

import org.apache.ibatis.annotations.Param;

public interface InternalControlMapper {

    Integer probe();

    int deleteExpiredNonces();

    int insertNonce(
            @Param("service") String service, @Param("nonce") String nonce, @Param("ttlSeconds") long ttlSeconds);
}
