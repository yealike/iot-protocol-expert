package top.yealike.pep.control.access.policy.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.access.policy.domain.PolicyRule;

public interface PermissionPolicyMapper {

    List<PolicyRule> selectEnabled(@Param("environment") String environment);

    List<String> selectLatestVersion();
}
