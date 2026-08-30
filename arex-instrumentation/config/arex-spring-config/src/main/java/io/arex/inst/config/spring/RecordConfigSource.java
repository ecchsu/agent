package io.arex.inst.config.spring;

import io.arex.inst.runtime.log.LogManager;
import io.arex.inst.runtime.serializer.Serializer;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java records reject reflective writes to their own components (verified: {@code Field.set()}
 * throws {@code IllegalAccessException} even with {@code setAccessible(true)}, unlike an ordinary
 * final field) - so a record-typed {@code @ConfigurationProperties} source can't be mutated in
 * place during replay. Instead, replay builds a brand-new instance through the record's canonical
 * constructor, substituting recorded values for recorded components and reusing the live
 * instance's own current value (via its accessor) for any component not recorded this time -
 * {@link SpringBeanConfigExtractor} then swaps the reference in whatever ordinary field holds it
 * (see {@link SpringBeanConfigRegistry#recordHolderFields()}), which is an unrestricted reference
 * write.
 */
final class RecordConfigSource {

    private RecordConfigSource() {
    }

    /**
     * {@code Class.isRecord()} is a Java 16 API. This module compiles with source/target (not
     * --release) inherited from the root pom, so a direct call compiles fine against whatever JDK
     * builds the agent but would throw {@code NoSuchMethodError} at runtime if the agent attaches
     * to an older target JVM. Guarded so an old target app degrades to "no record support" instead
     * of crashing the startup scan.
     */
    static boolean isRecordSafe(Class<?> type) {
        try {
            return type.isRecord();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Serializes every component of a record instance into a name-&gt;serialized-value map, for
     * storage under the holder field's own aggregate key. Returns null if the record can't be
     * introspected (fail-open, same as any other unrecordable field).
     *
     * <p>Uses the plain {@link Serializer#serialize(Object)}, not {@code serializeWithType()} -
     * that variant's mapper has {@code activateDefaultTyping(NON_FINAL)} active, which embeds a
     * type marker for non-final runtime types but skips {@code final} ones. A component built via
     * {@code Stream.toList()} (Java 16+) is a {@code java.util.ImmutableCollections$ListN}, a
     * JDK-internal final class - it serializes as a bare, unmarked JSON array, asymmetric with a
     * sibling {@code List} component that Spring Boot's YAML binder populated as a plain
     * (non-final) {@code ArrayList}, which does get the marker. {@link #reconstruct} already knows
     * each component's exact declared type from {@code RecordComponent} - unlike a genuinely
     * polymorphic field, there's no ambiguity here for the type marker to resolve, so the plain
     * serializer (which never activates default typing) sidesteps the asymmetry entirely instead
     * of trying to make the marker-based one handle it.
     */
    static String serializeComponents(Object recordInstance) {
        try {
            RecordComponent[] components = recordInstance.getClass().getRecordComponents();
            Map<String, String> componentValues = new LinkedHashMap<>();
            for (RecordComponent component : components) {
                component.getAccessor().setAccessible(true);
                Object value = component.getAccessor().invoke(recordInstance);
                if (value != null) {
                    componentValues.put(component.getName(), Serializer.serialize(value));
                }
            }
            return Serializer.serialize(componentValues);
        } catch (Throwable t) {
            LogManager.warn("spring.bean.config.record.capture.error", t);
            return null;
        }
    }

    /**
     * Builds a new instance of recordType via its canonical constructor. Each component's value
     * comes from recordedComponents (deserialized) if present there by name, otherwise from
     * currentInstance's own accessor - so a partially-recorded case never regresses an untouched
     * component to null/default. Falls open to currentInstance itself (a no-op reference swap) if
     * reconstruction fails for any reason, e.g. a compact constructor rejecting a replayed value.
     *
     * <p>Deserializes with the component's own declared generic type ({@code
     * component.getGenericType()}, preserving e.g. {@code List<String>} rather than the raw
     * erased {@code List}), via the plain {@link Serializer#deserialize(String, java.lang.reflect.Type)}
     * - not {@code deserializeWithType()}, which always targets bare {@code Object.class} and
     * expects the marker-based wrapping {@link #serializeComponents} deliberately no longer
     * produces (see that method's javadoc). Matches the same fix already applied to plain enum
     * fields in {@code SpringBeanConfigExtractor}.
     */
    static Object reconstruct(Class<?> recordType, Object currentInstance, Map<String, String> recordedComponents) {
        try {
            RecordComponent[] components = recordType.getRecordComponents();
            Class<?>[] componentTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                componentTypes[i] = component.getType();
                String raw = recordedComponents == null ? null : recordedComponents.get(component.getName());
                component.getAccessor().setAccessible(true);
                args[i] = raw != null ? Serializer.deserialize(raw, component.getGenericType()) : component.getAccessor().invoke(currentInstance);
            }
            Constructor<?> canonicalConstructor = recordType.getDeclaredConstructor(componentTypes);
            canonicalConstructor.setAccessible(true);
            return canonicalConstructor.newInstance(args);
        } catch (Throwable t) {
            LogManager.warn("spring.bean.config.record.reconstruct.error", t);
            return currentInstance;
        }
    }
}
