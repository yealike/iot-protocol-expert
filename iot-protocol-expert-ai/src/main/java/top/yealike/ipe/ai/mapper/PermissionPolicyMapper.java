package top.yealike.ipe.ai.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ai.policy.PolicyService;

public interface PermissionPolicyMapper {

    List<PolicyService.Rule> selectEnabled(@Param("environment") String environment);

    List<String> selectLatestVersion();
}
