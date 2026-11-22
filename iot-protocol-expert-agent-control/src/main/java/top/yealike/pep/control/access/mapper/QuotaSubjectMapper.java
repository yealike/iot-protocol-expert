package top.yealike.pep.control.access.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.access.domain.QuotaSubject;

public interface QuotaSubjectMapper {

    List<QuotaSubject> findQuotaUsers(@Param("query") String query);

    List<QuotaSubject> findQuotaServiceAccounts(@Param("query") String query);
}
