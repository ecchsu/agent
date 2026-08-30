package io.arex.inst.config.spring;

import io.arex.inst.runtime.serializer.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class RecordConfigSourceTest {

    record Fixture(String name, int count) {
    }

    record ListFixture(List<String> items) {
        public ListFixture {
            // matches the real bug's shape: a component built via a stream that ends in
            // toList() - Stream.toList() (Java 16+) returns an ImmutableCollections$ListN, a
            // JDK-internal *final* class, unlike the plain ArrayList a caller might pass in.
            items = items.stream().toList();
        }
    }

    static class NotARecord {
    }

    MockedStatic<Serializer> serializerMock;

    @BeforeEach
    void setUp() {
        serializerMock = Mockito.mockStatic(Serializer.class);
        // These fixtures only use scalar/list values, so a plain string round-trip is enough to
        // exercise the branching in RecordConfigSource without depending on the real Jackson/Gson
        // serializer wiring (which needs its own Builder setup this test doesn't need).
        // getArgument(0) explicitly typed as Object below - otherwise the compiler infers a
        // generic T from context and can resolve String.valueOf(char[]) instead of (Object),
        // since nothing else pins the type parameter (a well-known Mockito/generics gotcha).
        // A Map argument specifically is the aggregate serializeComponents() call, not a
        // per-component one - distinguished here the same way SpringBeanConfigExtractorTest does.
        serializerMock.when(() -> Serializer.serialize(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof Map) {
                return "serialized-map";
            }
            if (arg instanceof List) {
                return String.join(",", (List<String>) arg);
            }
            return String.valueOf(arg);
        });
        serializerMock.when(() -> Serializer.deserialize(anyString(), any(Type.class))).thenAnswer(inv -> {
            String raw = inv.getArgument(0);
            Type type = inv.getArgument(1);
            if (type == int.class || type == Integer.class) {
                return Integer.parseInt(raw);
            }
            if (type == List.class || type.getTypeName().startsWith("java.util.List")) {
                return List.of(raw.split(","));
            }
            return raw;
        });
    }

    @AfterEach
    void tearDown() {
        serializerMock.close();
    }

    @Test
    void isRecordSafe_trueForRecord() {
        assertTrue(RecordConfigSource.isRecordSafe(Fixture.class));
    }

    @Test
    void isRecordSafe_falseForOrdinaryClass() {
        assertFalse(RecordConfigSource.isRecordSafe(NotARecord.class));
    }

    @Test
    void reconstruct_usesRecordedValueWhenPresent() {
        Fixture current = new Fixture("old-name", 1);
        Map<String, String> recorded = new LinkedHashMap<>();
        recorded.put("name", "new-name");
        recorded.put("count", "5");

        Object result = RecordConfigSource.reconstruct(Fixture.class, current, recorded);

        assertEquals(new Fixture("new-name", 5), result);
    }

    @Test
    void reconstruct_fallsBackToLiveValueForUnrecordedComponent() {
        Fixture current = new Fixture("old-name", 7);
        Map<String, String> recorded = new LinkedHashMap<>();
        recorded.put("name", "new-name"); // "count" deliberately not recorded

        Object result = RecordConfigSource.reconstruct(Fixture.class, current, recorded);

        assertEquals(new Fixture("new-name", 7), result, "unrecorded component must reuse the live value, not regress to a default");
    }

    @Test
    void reconstruct_failsOpenToCurrentInstanceOnError() {
        // an instance of the wrong type makes the accessor invocation throw - reconstruct must
        // not propagate that, it must fall open to whatever was passed in as "current"
        Object current = "not-a-fixture-instance";

        Object result = RecordConfigSource.reconstruct(Fixture.class, current, null);

        assertEquals(current, result);
    }

    @Test
    void serializeComponents_serializesEveryComponentByName() {
        // relies on setUp()'s stub distinguishing the aggregate Map call from the per-component
        // scalar calls - a single blanket stub here would clobber the per-component values with
        // whatever the aggregate call returns, since both go through the same mocked method.
        Fixture fixture = new Fixture("alpha", 3);

        String result = RecordConfigSource.serializeComponents(fixture);

        assertEquals("serialized-map", result);
        // 3 calls total: one per scalar component, plus one for the aggregate map itself - only
        // the latter is what this test cares about, so capture all and pick out the Map one.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        serializerMock.verify(() -> Serializer.serialize(captor.capture()), Mockito.times(3));
        @SuppressWarnings("unchecked")
        Map<String, String> aggregateMap = (Map<String, String>) captor.getAllValues().stream()
                .filter(v -> v instanceof Map)
                .findFirst()
                .orElseThrow();
        assertEquals("alpha", aggregateMap.get("name"));
        assertEquals("3", aggregateMap.get("count"));
    }

    @Test
    void serializeComponents_failsOpenToNullOnError() {
        Object notARecordInstance = "oops";

        // getClass().getRecordComponents() on a non-record returns null, so iterating it throws
        // NullPointerException internally - must be caught, not propagated
        String result = RecordConfigSource.serializeComponents(notARecordInstance);

        assertEquals(null, result);
    }

    @Test
    void reconstruct_roundTripsAListComponentThatIsAJdkInternalFinalClass() {
        // regression test for the actual production bug (Issue 3, 2026-08 real-app testing): a
        // record component built via Stream.toList() is a java.util.ImmutableCollections$ListN, a
        // JDK-internal *final* class. The old serializeWithType()/deserializeWithType() path
        // embedded a type marker for this component but not for a plain ArrayList sibling
        // (Spring Boot's YAML binder produces those non-final), and deserializeWithType() always
        // targets bare Object.class - so a bare, unmarked array's first element got misread as a
        // type-id string and threw InvalidTypeIdException, which reconstruct() then swallowed and
        // fell open to currentInstance, silently dropping the recorded value. The plain
        // serialize()/deserialize(String, Type) path used now never embeds a type marker, so
        // there's no asymmetry to trip over.
        ListFixture current = new ListFixture(new java.util.ArrayList<>(List.of("live-a", "live-b")));
        Map<String, String> recorded = new LinkedHashMap<>();
        recorded.put("items", "rec-a,rec-b,rec-c");

        Object result = RecordConfigSource.reconstruct(ListFixture.class, current, recorded);

        assertEquals(new ListFixture(List.of("rec-a", "rec-b", "rec-c")), result);
    }
}
