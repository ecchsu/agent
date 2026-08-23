package io.arex.inst.config.spring;

import io.arex.inst.runtime.log.LogManager;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Populated exactly once per JVM, right after Spring context startup completes (see
 * SpringApplicationRunInstrumentation), by reflectively scanning every already-instantiated
 * singleton bean for fields that are either @Value-annotated or belong to a
 * @ConfigurationProperties class. Read (never modified) afterward, once per request, by
 * SpringBeanConfigExtractor.
 */
public class SpringBeanConfigRegistry {

    private static final Map<String, Object> BEAN_INSTANCES = new ConcurrentHashMap<>();
    private static final Map<String, List<Field>> BEAN_FIELDS = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    private SpringBeanConfigRegistry() {
    }

    public static void initialize(ConfigurableApplicationContext context) {
        if (initialized || context == null) {
            return;
        }
        synchronized (SpringBeanConfigRegistry.class) {
            if (initialized) {
                return;
            }
            try {
                scan(context.getBeanFactory());
            } finally {
                // even on partial failure, don't retry on every subsequent SpringApplication.run()
                initialized = true;
            }
        }
    }

    /**
     * Spring Boot itself registers a large number of internal @ConfigurationProperties beans
     * (ServerProperties, WebMvcProperties, JacksonProperties, TaskExecutionProperties, ...) for
     * its own auto-configuration. These aren't application config in any meaningful sense, they
     * bloat the recorded payload considerably, and several hold fields typed as ClassLoader/File/
     * ClassPathResource/etc. that reflective set() during replay could throw on - which, given
     * one failure rolls back the whole request's overrides (see SpringBeanConfigExtractor),
     * would silently break replay for the application's own fields too. Excluding anything under
     * org.springframework.* is a simple, reliable way to keep this feature scoped to application
     * code, since no real application defines its own beans in that namespace.
     */
    private static final String FRAMEWORK_PACKAGE_PREFIX = "org.springframework.";

    private static void scan(ConfigurableListableBeanFactory beanFactory) {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Object bean = beanFactory.containsSingleton(beanName) ? beanFactory.getSingleton(beanName) : null;
            if (bean == null) {
                continue; // not a singleton, or not yet instantiated (e.g. lazy) - out of scope for now
            }
            if (bean.getClass().getName().startsWith(FRAMEWORK_PACKAGE_PREFIX)) {
                continue;
            }
            List<Field> fields = eligibleFields(bean.getClass());
            if (!fields.isEmpty()) {
                BEAN_INSTANCES.put(beanName, bean);
                BEAN_FIELDS.put(beanName, fields);
            }
        }
        LogManager.info("spring.bean.config.registry",
                "registered " + BEAN_INSTANCES.size() + " bean(s) with @Value/@ConfigurationProperties "
                        + "fields, covering " + BEAN_FIELDS.values().stream().mapToInt(List::size).sum()
                        + " field(s) total: " + BEAN_INSTANCES.keySet());
    }

    private static List<Field> eligibleFields(Class<?> beanClass) {
        boolean isConfigurationProperties = beanClass.isAnnotationPresent(ConfigurationProperties.class);
        List<Field> fields = new ArrayList<>();
        Class<?> current = beanClass;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                boolean isValueField = field.isAnnotationPresent(org.springframework.beans.factory.annotation.Value.class);
                if (isConfigurationProperties || isValueField) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    public static Map<String, Object> beanInstances() {
        return Collections.unmodifiableMap(BEAN_INSTANCES);
    }

    public static Map<String, List<Field>> beanFields() {
        return Collections.unmodifiableMap(BEAN_FIELDS);
    }
}
