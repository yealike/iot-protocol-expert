package top.yealike.pep.control.config;

import java.util.UUID;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.yealike.pep.control.mybatis.UuidTypeHandler;

@Configuration
public class MyBatisConfiguration {
    @Bean
    ConfigurationCustomizer uuidTypeHandler() {
        return configuration -> configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
    }
}
