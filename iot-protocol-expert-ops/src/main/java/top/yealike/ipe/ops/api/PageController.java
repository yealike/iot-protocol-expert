package top.yealike.ipe.ops.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 入口。
 *
 * <p>前端是 Vite + React 的 history 路由，刷新子路由时服务端必须回落到 index.html。 这里用显式路由白名单，而不是通配转发——通配会把 {@code
 * /api/**} 的 404 也吞成 HTML， 前端拿到的就不再是 JSON 错误了。
 */
@Controller
public class PageController {

    @GetMapping({"/", "/overview", "/devices", "/devices/**", "/products", "/products/**", "/alarms", "/campus"})
    public String spa() {
        return "forward:/index.html";
    }

    /** 旧路径兼容：改造前平台挂在 /ops。 */
    @GetMapping("/ops")
    public String legacyOps() {
        return "redirect:/overview";
    }
}
