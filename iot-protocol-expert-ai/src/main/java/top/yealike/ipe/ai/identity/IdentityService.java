package top.yealike.ipe.ai.identity;

import org.springframework.stereotype.Service;
import top.yealike.ipe.ai.platform.identity.OpsIdentityClient;

/**
 * 身份校验：把浏览器的会话 cookie 转发给 ops 认证入口，用返回值建立本服务身份。
 *
 * <p>AI 服务不自行签发身份、不解析前端角色头。ops 是唯一可信身份来源，网关认证也不替代这一步。
 */
@Service
public class IdentityService {

    private final OpsIdentityClient identities;

    public IdentityService(OpsIdentityClient identities) {
        this.identities = identities;
    }

    public UserIdentity resolve(String cookieHeader) {
        return identities.resolveCookie(cookieHeader);
    }

    /** 任务执行期间不保存用户 cookie，使用服务身份向 ops 读取最新账号状态。 */
    public UserIdentity resolveActor(String id) {
        return identities.resolveActor(id);
    }
}
