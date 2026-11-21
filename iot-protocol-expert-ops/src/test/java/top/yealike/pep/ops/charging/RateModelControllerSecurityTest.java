package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import top.yealike.pep.ops.api.admin.RateModelController;
import top.yealike.pep.ops.api.dto.OpsDtos;

class RateModelControllerSecurityTest {

    @Test
    void managementEndpointsUseExpectedPermissions() throws Exception {
        Method list = RateModelController.class.getMethod("list");
        Method save = RateModelController.class.getMethod("save", OpsDtos.RateModelView.class);
        Method activate = RateModelController.class.getMethod("activate", String.class);

        assertEquals(
                "/api/ops/rate-models",
                RateModelController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals(
                "hasAuthority('ops:read')",
                list.getAnnotation(PreAuthorize.class).value());
        assertEquals(
                "hasAuthority('ops:manage')",
                save.getAnnotation(PreAuthorize.class).value());
        assertEquals(
                "hasAuthority('ops:manage')",
                activate.getAnnotation(PreAuthorize.class).value());
        assertEquals(
                "/{modelNo}/activate", activate.getAnnotation(PostMapping.class).value()[0]);
        assertEquals(0, list.getAnnotation(GetMapping.class).value().length);
        assertEquals(0, save.getAnnotation(PostMapping.class).value().length);
    }
}
