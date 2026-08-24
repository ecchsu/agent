package io.arex.inst.config.spring;

import io.arex.inst.runtime.serializer.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class RecordConfigSourceTest {

    record Fixture(String name, int count) {
    }

    static class NotARecord {
    }

    MockedStatic<Serializer> serializerMock;

    @BeforeEach
    void setUp() {
        serializerMock = Mockito.mockStatic(Serializer.class);
        // These fixtures only use scalar values, so a plain string round-trip is enough to
        // exercise the branching in RecordConfigSource without depending on the real Jackson/Gson
        // serializer wiring (which needs its own Builder setup this test doesn't need).
        // getArgument(0) explicitly typed as Object below - otherwise the compiler infers a
        // generic T from context and can resolve String.valueOf(char[]) instead of (Object),
        // since nothing else pins the type parameter (a well-known Mockito/generics gotcha).
        serializerMock.when(() -> Serializer.serializeWithType(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            return String.valueOf(arg);
        });
        serializerMock.when(() -> Serializer.deserializeWithType(anyString())).thenAnswer(inv -> {
            String raw = inv.getArgument(0);
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return raw;
            }
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
        Fixture fixture = new Fixture("alpha", 3);
        serializerMock.when(() -> Serializer.serialize(any())).thenReturn("serialized-map");

        String result = RecordConfigSource.serializeComponents(fixture);

        assertEquals("serialized-map", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        serializerMock.verify(() -> Serializer.serialize(captor.capture()));
        assertEquals("alpha", captor.getValue().get("name"));
        assertEquals("3", captor.getValue().get("count"));
    }

    @Test
    void serializeComponents_failsOpenToNullOnError() {
        Object notARecordInstance = "oops";

        // getClass().getRecordComponents() on a non-record returns null, so iterating it throws
        // NullPointerException internally - must be caught, not propagated
        String result = RecordConfigSource.serializeComponents(notARecordInstance);

        assertEquals(null, result);
    }
}
