package top.yealike.pep.control.access.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface QuotaSubjectMapper {

    record QuotaSubject(String id, String code, String name, String state, String context) {}

    List<QuotaSubject> findQuotaUsers(@Param("query") String query);

    List<QuotaSubject> findQuotaServiceAccounts(@Param("query") String query);
}
