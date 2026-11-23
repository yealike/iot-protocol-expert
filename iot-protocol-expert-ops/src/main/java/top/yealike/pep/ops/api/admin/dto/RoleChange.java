package top.yealike.pep.ops.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "角色权限变更")
public record RoleChange(@Schema(description = "权限编码列表") List<String> permissions) {}
