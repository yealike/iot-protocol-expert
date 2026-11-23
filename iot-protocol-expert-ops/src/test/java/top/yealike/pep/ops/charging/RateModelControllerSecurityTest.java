package top.yealike.pep.ops.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import top.yealike.pep.ops.api.admin.RateModelController;
import top.yealike.pep.ops.charging.dto.RateModelView;

class RateModelControllerSecurityTest {

    @Test
    void managementEndpointsUseExpectedPermissions() throws Exception {
        Method list = RateModelController.class.getMethod("list");
        Method save = RateModelController.class.getMethod("save", RateModelView.class);

        assertEquals(
                "/api/ops/rate-models",
                RateModelController.class.getAnnotation(RequestMapping.class).path()[0]);
        assertEquals(
                "hasAuthority('ops:read')",
                list.getAnnotation(PreAuthorize.class).value());
        assertEquals(
                "hasAuthority('ops:manage')",
                save.getAnnotation(PreAuthorize.class).value());
        assertEquals(0, list.getAnnotation(GetMapping.class).value().length);
        assertEquals(
                0,
                save.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                        .value()
                        .length);
    }
}
