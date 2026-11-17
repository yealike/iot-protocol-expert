package top.yealike.pep.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        classes = AgentControlApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:module-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.hikari.connection-init-sql=",
            "spring.flyway.enabled=false",
            "ipe.ai.bootstrap.enabled=false",
            "ipe.ai.platform.bootstrap.enabled=false",
            "ipe.ai.platform.registry.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false",
            "ipe.observability.enabled=false",
            "management.tracing.export.otlp.enabled=false",
            "management.logging.export.otlp.enabled=false",
            "management.otlp.metrics.export.enabled=false"
        })
class AgentControlApplicationContextTest {
    @Autowired
    private ApplicationContext context;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private top.yealike.pep.control.agents.publication.PublicationCoordinator publications;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private top.yealike.pep.control.runs.RunScheduler runs;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private top.yealike.pep.control.evaluations.EvaluationWorker evaluations;

    @Test
    void startsWithItsOwnServicesAndSecurity() {
        assertThat(context.containsBean("agentService")).isTrue();
        assertThat(context.containsBean("orderService")).isFalse();
        assertThat(context.containsBean("identityService")).isFalse();
        assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class))
                .hasSize(1);
    }
}
