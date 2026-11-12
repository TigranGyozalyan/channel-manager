package com.tgyozalyan.springutils.swagger;

import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.configuration.SpringDocRequiredModule;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

@Configuration
@EnableConfigurationProperties
public class SpringDocContext {

    /**
     * Some spring classes show up in swagger schema defs because of return type wrappers and injected params in controllers.
     */
    private static final Set<String> EXCLUDED_SCHEMA_CLASSES = Set.of(
            "HttpMethod", "HttpRange", "HttpStatusCode", "HttpHeaders",
            "ResponseEntityResource", "ContentDisposition", "MediaType"
    );

    /**
     * SpringBoot is horrible, but when combined with SpringDoc/Swagger, it seems to be at its worst:
     * 1. This bean is simultaneously marked with @ConfigurationProperties and @Configuration. I don't know
     *    what these geniuses tried to achieve with this.
     * 2. This causes the bean defined in XML to be used as a @Configuration, and therefore the bean itself gets
     *    deregistered and can't be injected into other SpringDoc classes. Hence, we need to declare it in Java
     *    configuration - for some reason this keeps the bean in the BeanFactory (why?!).
     * 3. To make things worse the class is marked with @Conditional's - if we declare it as
     *    {@code @EnableConfigurationProperties} (so that it could be used as the source of properties), it still can
     *    act as @Configuration, but again - the bean gets deregistered.
     * 4. This all makes it possible to register bean only if we put {@code @EnableConfigurationProperties} without
     *    declaring the class there, but at the same time define it explicitly as {@code @Bean} here.
     */
    @Bean
    public SpringDocConfigProperties springDocConfigProperties() {
        SpringDocConfigProperties props = new SpringDocConfigProperties();
        props.setOverrideWithGenericResponse(false);
        return props;
    }

    @Bean
    public GlobalOpenApiCustomizer excludeSpringInternalSchemas() {
        return openApi -> {
            if (openApi.getComponents() == null) return;
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            if (schemas != null) {
                EXCLUDED_SCHEMA_CLASSES.forEach(schemas::remove);
            }
        };
    }

    @Bean
    public ObjectMapperProvider objectMapperProvider(SpringDocConfigProperties props) {
        // TODO: need to create our own ObjectMapperProvider, because right now what we use in SpringMVC and what
        //       Swagger uses are different ObjectMappers. E.g. Swagger can detect getXxx() methods while our
        //       ObjectMapper won't.
        ObjectMapperProvider result = new ObjectMapperProvider(props);
        result.jsonMapper().registerModule(new SpringDocRequiredModule());
        return result;
    }
}
