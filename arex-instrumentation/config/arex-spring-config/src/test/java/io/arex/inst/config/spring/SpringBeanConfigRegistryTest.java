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

    @Test
    void initialize_leavesPlainAnnotationBasedRegistrationUnaffected() {
        PlainBean plain = new PlainBean();

        SpringBeanConfigRegistry.initialize(contextFor(Map.of("plain", plain)));

        assertTrue(SpringBeanConfigRegistry.beanInstances().containsKey("plain"));
        assertEquals(1, SpringBeanConfigRegistry.beanFields().get("plain").size());
        assertTrue(SpringBeanConfigRegistry.recordHolderFields().isEmpty());
    }
}
