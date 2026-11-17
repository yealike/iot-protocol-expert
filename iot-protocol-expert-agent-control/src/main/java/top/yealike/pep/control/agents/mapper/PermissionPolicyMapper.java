package top.yealike.pep.control.agents.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.access.policy.PolicyService;

public interface PermissionPolicyMapper {

    List<PolicyService.Rule> selectEnabled(@Param("environment") String environment);

    List<String> selectLatestVersion();
}
