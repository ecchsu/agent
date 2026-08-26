package io.arex.inst.config.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBeanConfigRegistryTest {

    @ConfigurationProperties(prefix = "fixture")
    record FixtureRecordProperties(String value) {
    }

    static class HolderBean {
        private final FixtureRecordProperties props;

        HolderBean(FixtureRecordProperties props) {
            this.props = props;
        }
    }

    static class PlainBean {
        @Value("${fixture.plain}")
        private String plainValue = "unused";
    }

    /**
     * initialized/BEAN_INSTANCES/BEAN_FIELDS/RECORD_HOLDER_FIELDS are static and populated only
     * once per JVM by design (see the class javadoc) - reset them via reflection before each test
     * so tests don't leak state into each other.
     */
    @BeforeEach
    void resetStaticState() throws Exception {
        clearStaticMap("BEAN_INSTANCES");
        clearStaticMap("BEAN_FIELDS");
        clearStaticMap("RECORD_HOLDER_FIELDS");
        Field initialized = SpringBeanConfigRegistry.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(null, false);
    }

    private static void clearStaticMap(String fieldName) throws Exception {
        Field field = SpringBeanConfigRegistry.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).clear();
    }

    private static ConfigurableApplicationContext contextFor(Map<String, Object> beans) {
        ConfigurableListableBeanFactory beanFactory = Mockito.mock(ConfigurableListableBeanFactory.class);
        Mockito.when(beanFactory.getBeanDefinitionNames()).thenReturn(beans.keySet().toArray(new String[0]));
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Mockito.when(beanFactory.containsSingleton(entry.getKey())).thenReturn(true);
            Mockito.when(beanFactory.getSingleton(entry.getKey())).thenReturn(entry.getValue());
        }
        ConfigurableApplicationContext context = Mockito.mock(ConfigurableApplicationContext.class);
        Mockito.when(context.getBeanFactory()).thenReturn(beanFactory);
        return context;
    }

    @Test
    void initialize_registersRecordHolderField_forBeanWithNoOtherEligibleFields() throws Exception {
        FixtureRecordProperties recordSource = new FixtureRecordProperties("hello");
        HolderBean holder = new HolderBean(recordSource);

        SpringBeanConfigRegistry.initialize(contextFor(Map.of("recordSource", recordSource, "holder", holder)));

        assertTrue(SpringBeanConfigRegistry.beanInstances().containsKey("holder"),
                "holder has no @Value/@ConfigurationProperties field of its own - it must still be "
                        + "registered because it holds a reference to a record-typed config source");

        List<Field> holderFields = SpringBeanConfigRegistry.beanFields().get("holder");
        assertEquals(1, holderFields.size());
        Field propsField = holderFields.get(0);
        assertEquals("props", propsField.getName());
        assertEquals(FixtureRecordProperties.class, SpringBeanConfigRegistry.recordHolderFields().get(propsField));
    }

    @Test
    void initialize_recordSourceItselfIsNotTreatedAsItsOwnHolder() {
        FixtureRecordProperties recordSource = new FixtureRecordProperties("hello");

        SpringBeanConfigRegistry.initialize(contextFor(Map.of("recordSource", recordSource)));

        assertTrue(SpringBeanConfigRegistry.recordHolderFields().isEmpty());
    }

    /**
     * Before this fix, a record source's own component fields got registered in BEAN_FIELDS via
     * eligibleFields() (the class is @ConfigurationProperties-annotated) but were never added to
     * RECORD_HOLDER_FIELDS - only a *holder* bean's reference field is. So on every replay,
     * applyReplayOverrides() found no record-holder mapping for these fields, fell through to the
     * plain scalar path, and field.set() threw IllegalAccessException for every one of them,
     * every request - confirmed in a real app's log as "arex.spring.bean.config.field.write" /
     * IllegalAccessException, one per component. There is no in-place update path for these
     * fields at all - only holder-swap works - so scan() now skips registering the record
     * source's own bean entirely (see the matching test below for the holder path, still working).
     */
    @Test
    void initialize_doesNotRegisterRecordSourceOwnFields() {
        FixtureRecordProperties recordSource = new FixtureRecordProperties("hello");

        SpringBeanConfigRegistry.initialize(contextFor(Map.of("recordSource", recordSource)));

        assertTrue(SpringBeanConfigRegistry.beanInstances().isEmpty(),
                "a record source's own bean entry has no in-place update path and must not be "
                        + "registered - only a holder bean's reference field can be swapped");
        assertTrue(SpringBeanConfigRegistry.beanFields().isEmpty());
    }

    @Test
    void initialize_leavesPlainAnnotationBasedRegistrationUnaffected() {
        PlainBean plain = new PlainBean();

        SpringBeanConfigRegistry.initialize(contextFor(Map.of("plain", plain)));

        assertTrue(SpringBeanConfigRegistry.beanInstances().containsKey("plain"));
        assertEquals(1, SpringBeanConfigRegistry.beanFields().get("plain").size());
        assertTrue(SpringBeanConfigRegistry.recordHolderFields().isEmpty());
    }

    /**
     * Confirmed experimentally (not just reasoned about): a reflective write to a CGLIB proxy's
     * own inherited field never reaches the wrapped target - a subsequent method call, which
     * delegates to the real target, still returns the pre-write value. Every reflective
     * read/write this feature performs needs the real target instance, not the proxy.
     */
    @Test
    void initialize_unwrapsAopProxiedBeanToItsRealTargetInstance() {
        PlainBean plain = new PlainBean();
        org.springframework.aop.framework.ProxyFactory proxyFactory = new org.springframework.aop.framework.ProxyFactory(plain);
        proxyFactory.setProxyTargetClass(true);
        Object proxy = proxyFactory.getProxy();

        SpringBeanConfigRegistry.initialize(contextFor(Map.of("plain", proxy)));

        Object registered = SpringBeanConfigRegistry.beanInstances().get("plain");
        assertTrue(registered == plain, "a proxied bean must be unwrapped to its real target "
                + "instance so reflective field access actually reaches the wrapped target");
    }

    /**
     * Every {@code @Configuration} class is CGLIB-enhanced by Spring by default (to enforce
     * singleton semantics between its own {@code @Bean} methods), whether or not it's also an
     * AOP-advised bean - the enhanced instance's runtime class has no loadable {@code .class}
     * resource, exactly like an AOP proxy, but doesn't implement {@code Advised} at all. A
     * plain-fixture unit test (a config class instantiated with a bare {@code new}) can't
     * exercise this - it was found only by bootstrapping a real Spring context. Regression guard:
     * boots a minimal real context with a record-typed {@code @ConfigurationProperties} source
     * injected directly as a {@code @Bean} factory method parameter, its accessor's return value
     * passed straight into another bean's constructor - the exact shape that silently found zero
     * matches when class resolution didn't survive {@code @Configuration} enhancement.
     */
    @Test
    void initialize_findsRecordAccessorDerivedField_throughRealConfigurationClassEnhancement() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext realContext =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext(RealConfig.class)) {
            Map<String, Object> beans = Map.of(
                    "props", realContext.getBean(RealConfig.RecordSource.class),
                    "config", realContext.getBean(RealConfig.class),
                    "target", realContext.getBean(RealConfig.Target.class));

            SpringBeanConfigRegistry.initialize(contextFor(beans));

            List<Field> targetFields = SpringBeanConfigRegistry.beanFields().get("target");
            assertTrue(targetFields != null && targetFields.stream().anyMatch(f -> f.getName().equals("value")),
                    "expected the record-accessor-derived field on the real @Configuration-enhanced "
                            + "factory's target bean to be discovered");
        }
    }

    @org.springframework.context.annotation.Configuration
    static class RealConfig {
        @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "realconfig")
        record RecordSource(String value) {
        }

        @org.springframework.context.annotation.Bean
        RecordSource recordSource() {
            return new RecordSource("hello");
        }

        static class Target {
            private final String value;

            Target(String value) {
                this.value = value;
            }
        }

        @org.springframework.context.annotation.Bean
        Target target(RecordSource recordSource) {
            return new Target(recordSource.value());
        }
    }
}
