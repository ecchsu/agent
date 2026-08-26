package io.arex.inst.config.spring;

import io.arex.agent.bootstrap.model.ArexMocker;
import io.arex.agent.bootstrap.model.MockCategoryType;
import io.arex.agent.bootstrap.model.Mocker;
import io.arex.inst.runtime.context.ContextManager;
import io.arex.inst.runtime.serializer.Serializer;
import io.arex.inst.runtime.util.MockUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Covers the two fixes added to captureAndRecord()/applyReplayOverrides(): a null-marker for
 * genuinely-null plain fields (a missing map entry is otherwise indistinguishable from "recorded
 * as null"), and enum-aware serialize/deserialize (the shared Serializer's default typing embeds
 * no type info for a plain enum - a final class - so a blind Object.class deserialize returns a
 * String, not the enum). ContextManager/MockUtils/Serializer are mocked with simple, naive
 * stand-ins rather than the real Jackson wiring, same convention as RecordConfigSourceTest -
 * these tests are about SpringBeanConfigExtractor's own branching, not the shared serializer.
 */
class SpringBeanConfigExtractorTest {

    enum Fixture {
        ASC, DESC
    }

    static class FixtureBean {
        String nullableValue;
        Fixture direction = Fixture.ASC;
        String plainValue = "hello";
    }

    private MockedStatic<ContextManager> contextManagerMock;
    private MockedStatic<MockUtils> mockUtilsMock;
    private MockedStatic<Serializer> serializerMock;
    private Map<String, String> capturedBody;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        clearRegistryState();

        contextManagerMock = Mockito.mockStatic(ContextManager.class);
        mockUtilsMock = Mockito.mockStatic(MockUtils.class);
        serializerMock = Mockito.mockStatic(Serializer.class);

        serializerMock.when(() -> Serializer.serialize(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof Map) {
                capturedBody = new HashMap<>((Map<String, String>) arg);
                return "serialized-aggregate";
            }
            return String.valueOf(arg);
        });
        serializerMock.when(() -> Serializer.serializeWithType(any())).thenAnswer(inv -> String.valueOf((Object) inv.getArgument(0)));
        serializerMock.when(() -> Serializer.deserializeWithType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        serializerMock.when(() -> Serializer.deserialize(anyString(), any(Class.class))).thenAnswer(inv -> {
            String raw = inv.getArgument(0);
            Class<?> type = inv.getArgument(1);
            if (type.isEnum()) {
                return Enum.valueOf((Class<Enum>) type, raw);
            }
            return raw;
        });

        mockUtilsMock.when(() -> MockUtils.createConfigFile(anyString())).thenAnswer(inv -> {
            ArexMocker mocker = new ArexMocker(MockCategoryType.CONFIG_FILE);
            mocker.setTargetRequest(new Mocker.Target());
            mocker.setTargetResponse(new Mocker.Target());
            return mocker;
        });
        mockUtilsMock.when(() -> MockUtils.recordMocker(any())).thenAnswer(inv -> null);
        mockUtilsMock.when(() -> MockUtils.replayBody(any())).thenAnswer(inv -> capturedBody);
    }

    @AfterEach
    void tearDown() {
        contextManagerMock.close();
        mockUtilsMock.close();
        serializerMock.close();
    }

    private static void clearRegistryState() throws Exception {
        clearStaticMap("BEAN_INSTANCES");
        clearStaticMap("BEAN_FIELDS");
        clearStaticMap("RECORD_HOLDER_FIELDS");
    }

    private static void clearStaticMap(String fieldName) throws Exception {
        Field field = SpringBeanConfigRegistry.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    private static void registerFixtureBean(String beanName, FixtureBean bean, String... fieldNames) throws Exception {
        Field instancesField = SpringBeanConfigRegistry.class.getDeclaredField("BEAN_INSTANCES");
        instancesField.setAccessible(true);
        ((Map<String, Object>) instancesField.get(null)).put(beanName, bean);

        List<Field> fields = new java.util.ArrayList<>();
        for (String fieldName : fieldNames) {
            Field f = FixtureBean.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            fields.add(f);
        }
        Field fieldsField = SpringBeanConfigRegistry.class.getDeclaredField("BEAN_FIELDS");
        fieldsField.setAccessible(true);
        ((Map<String, List<Field>>) fieldsField.get(null)).put(beanName, fields);
    }

    @Test
    void captureAndRecord_writesNullMarkerForGenuinelyNullField() throws Exception {
        contextManagerMock.when(ContextManager::needRecord).thenReturn(true);
        FixtureBean bean = new FixtureBean();
        bean.nullableValue = null;
        registerFixtureBean("fixtureBean", bean, "nullableValue");

        SpringBeanConfigExtractor.captureAndRecord();

        assertEquals("__AREX_NULL__", capturedBody.get("spring-config-properties:fixtureBean.nullableValue"));
    }

    @Test
    void applyReplayOverrides_setsFieldToNullForNullMarker() throws Exception {
        FixtureBean bean = new FixtureBean();
        bean.nullableValue = "should-be-overwritten";
        registerFixtureBean("fixtureBean", bean, "nullableValue");
        capturedBody = Map.of("spring-config-properties:fixtureBean.nullableValue", "__AREX_NULL__");
        contextManagerMock.when(ContextManager::needReplay).thenReturn(true);

        Object token = SpringBeanConfigExtractor.applyReplayOverrides();

        assertNull(bean.nullableValue, "the null marker must set the field to null, not skip it");

        SpringBeanConfigExtractor.restore(token);
        assertEquals("should-be-overwritten", bean.nullableValue, "restore must bring back the pre-replay value");
    }

    @Test
    void captureAndRecord_leavesGenuinelyAbsentFieldOutOfTheAggregate_regressionGuard() throws Exception {
        contextManagerMock.when(ContextManager::needRecord).thenReturn(true);
        FixtureBean bean = new FixtureBean();
        registerFixtureBean("fixtureBean", bean, "plainValue");

        SpringBeanConfigExtractor.captureAndRecord();

        assertEquals("hello", capturedBody.get("spring-config-properties:fixtureBean.plainValue"));
    }

    @Test
    void applyReplayOverrides_leavesFieldUntouchedWhenNoRecordedEntryExists_regressionGuard() throws Exception {
        FixtureBean bean = new FixtureBean();
        bean.plainValue = "local-value";
        registerFixtureBean("fixtureBean", bean, "plainValue");
        capturedBody = Map.of("spring-config-properties:some.other.field", "irrelevant");
        contextManagerMock.when(ContextManager::needReplay).thenReturn(true);

        SpringBeanConfigExtractor.applyReplayOverrides();

        assertEquals("local-value", bean.plainValue, "fail-open: no recorded entry must leave the field untouched");
    }

    @Test
    void captureAndRecord_serializesEnumFieldPlainly() throws Exception {
        contextManagerMock.when(ContextManager::needRecord).thenReturn(true);
        FixtureBean bean = new FixtureBean();
        bean.direction = Fixture.DESC;
        registerFixtureBean("fixtureBean", bean, "direction");

        SpringBeanConfigExtractor.captureAndRecord();

        assertEquals("DESC", capturedBody.get("spring-config-properties:fixtureBean.direction"));
    }

    @Test
    void applyReplayOverrides_roundTripsEnumFieldToTheCorrectConstant_notAString() throws Exception {
        FixtureBean bean = new FixtureBean();
        bean.direction = Fixture.ASC; // local/live value, must be overridden by the recorded one
        registerFixtureBean("fixtureBean", bean, "direction");
        capturedBody = Map.of("spring-config-properties:fixtureBean.direction", "DESC");
        contextManagerMock.when(ContextManager::needReplay).thenReturn(true);

        SpringBeanConfigExtractor.applyReplayOverrides();

        assertEquals(Fixture.DESC, bean.direction, "must deserialize into the actual enum constant, not a String");
    }
}
