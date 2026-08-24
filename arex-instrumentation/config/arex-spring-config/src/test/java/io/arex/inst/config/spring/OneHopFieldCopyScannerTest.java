package io.arex.inst.config.spring;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneHopFieldCopyScannerTest {

    // ---- positive fixture: mirrors the fetchGroup/GetFabImpl shape exactly ----

    static class Source {
        private final String fetchGroup;

        Source(String fetchGroup) {
            this.fetchGroup = fetchGroup;
        }

        Target makeTarget(Gateway gateway) {
            return new Target(gateway, fetchGroup);
        }
    }

    static class Gateway {
    }

    static class Target {
        private final Gateway gateway;
        private final String fetchGroup;

        Target(Gateway gateway, String fetchGroup) {
            this.gateway = gateway;
            this.fetchGroup = fetchGroup;
        }
    }

    // ---- negative fixture: transformation before the constructor call ----

    static class TransformSource {
        private final String value;

        TransformSource(String value) {
            this.value = value;
        }

        TransformTarget makeTarget() {
            return new TransformTarget(value.toUpperCase());
        }
    }

    static class TransformTarget {
        private final String value;

        TransformTarget(String value) {
            this.value = value;
        }
    }

    // ---- negative fixture: an extra helper-method hop before the constructor call ----

    static class HelperSource {
        private final String value;

        HelperSource(String value) {
            this.value = value;
        }

        HelperTarget makeTarget() {
            return new HelperTarget(resolve(value));
        }

        private static String resolve(String v) {
            return v;
        }
    }

    static class HelperTarget {
        private final String value;

        HelperTarget(String value) {
            this.value = value;
        }
    }

    // ---- negative fixture: a branch in the factory method ----

    static class BranchSource {
        private final String value;

        BranchSource(String value) {
            this.value = value;
        }

        BranchTarget makeTarget(boolean flag) {
            if (flag) {
                return new BranchTarget(value);
            }
            return new BranchTarget("default");
        }
    }

    static class BranchTarget {
        private final String value;

        BranchTarget(String value) {
            this.value = value;
        }
    }

    // ---- negative fixture: a branch inside the target constructor itself ----

    static class CtorBranchSource {
        private final String value;

        CtorBranchSource(String value) {
            this.value = value;
        }

        CtorBranchTarget makeTarget() {
            return new CtorBranchTarget(value);
        }
    }

    static class CtorBranchTarget {
        private final String value;

        CtorBranchTarget(String value) {
            if (value == null) {
                this.value = "default";
            } else {
                this.value = value;
            }
        }
    }

    private static Map<String, Object> beans(Object... namedPairs) {
        Map<String, Object> beans = new HashMap<>();
        for (int i = 0; i < namedPairs.length; i += 2) {
            beans.put((String) namedPairs[i], namedPairs[i + 1]);
        }
        return beans;
    }

    private static Map<String, List<Field>> sourceFields(String beanName, Field field) throws Exception {
        Map<String, List<Field>> map = new HashMap<>();
        field.setAccessible(true);
        map.put(beanName, List.of(field));
        return map;
    }

    @Test
    void findOneHopCopies_detectsDirectFieldToFieldPassthrough() throws Exception {
        Source source = new Source("team-a");
        Gateway gateway = new Gateway();
        Target target = source.makeTarget(gateway);

        Map<String, Object> applicationBeans = beans("source", source, "target", target);
        Field sourceField = Source.class.getDeclaredField("fetchGroup");

        Map<String, List<Field>> result = OneHopFieldCopyScanner.findOneHopCopies(
                applicationBeans, sourceFields("source", sourceField), Collections.emptyMap());

        assertTrue(result.containsKey("target"), "expected the target bean to gain a derived field");
        List<Field> derived = result.get("target");
        assertEquals(1, derived.size());
        assertEquals("fetchGroup", derived.get(0).getName());
    }

    @Test
    void findOneHopCopies_doesNotMatchWhenValueIsTransformed() throws Exception {
        TransformSource source = new TransformSource("value");
        TransformTarget target = source.makeTarget();

        Map<String, Object> applicationBeans = beans("source", source, "target", target);
        Field sourceField = TransformSource.class.getDeclaredField("value");

        Map<String, List<Field>> result = OneHopFieldCopyScanner.findOneHopCopies(
                applicationBeans, sourceFields("source", sourceField), Collections.emptyMap());

        assertTrue(result.isEmpty(), "a transformed value must not be treated as a direct passthrough");
    }

    @Test
    void findOneHopCopies_doesNotMatchThroughAHelperMethodHop() throws Exception {
        HelperSource source = new HelperSource("value");
        HelperTarget target = source.makeTarget();

        Map<String, Object> applicationBeans = beans("source", source, "target", target);
        Field sourceField = HelperSource.class.getDeclaredField("value");

        Map<String, List<Field>> result = OneHopFieldCopyScanner.findOneHopCopies(
                applicationBeans, sourceFields("source", sourceField), Collections.emptyMap());

        assertTrue(result.isEmpty(), "an extra method-call hop must not be treated as a one-hop passthrough");
    }

    @Test
    void findOneHopCopies_doesNotMatchWhenFactoryMethodBranches() throws Exception {
        BranchSource source = new BranchSource("value");
        BranchTarget target = source.makeTarget(true);

        Map<String, Object> applicationBeans = beans("source", source, "target", target);
        Field sourceField = BranchSource.class.getDeclaredField("value");

        Map<String, List<Field>> result = OneHopFieldCopyScanner.findOneHopCopies(
                applicationBeans, sourceFields("source", sourceField), Collections.emptyMap());

        assertTrue(result.isEmpty(), "a branch anywhere in the factory method must disqualify every match found in it");
    }

    @Test
    void findOneHopCopies_doesNotMatchWhenConstructorBranches() throws Exception {
        CtorBranchSource source = new CtorBranchSource("value");
        CtorBranchTarget target = source.makeTarget();

        Map<String, Object> applicationBeans = beans("source", source, "target", target);
        Field sourceField = CtorBranchSource.class.getDeclaredField("value");

        Map<String, List<Field>> result = OneHopFieldCopyScanner.findOneHopCopies(
                applicationBeans, sourceFields("source", sourceField), Collections.emptyMap());

        assertTrue(result.isEmpty(), "a branch inside the target constructor must disqualify the match");
    }

    @Test
    void findOneHopCopies_skipsRecordHolderFields() throws Exception {
        Source source = new Source("team-a");
        Field sourceField = Source.class.getDeclaredField("fetchGroup");
        Map<Field, Class<?>> recordHolderFields = new HashMap<>();
        recordHolderFields.put(sourceField, String.class); // pretend this field is a record holder

        Map<String, Object> applicationBeans = beans("source", source);
        Map<String, List<Field>> result = OneHopFieldCopyScanner.findOneHopCopies(
                applicationBeans, sourceFields("source", sourceField), recordHolderFields);

        assertTrue(result.isEmpty());
    }
}
