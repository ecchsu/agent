package io.arex.inst.config.spring;

import io.arex.agent.bootstrap.model.Mocker;
import io.arex.inst.runtime.context.ContextManager;
import io.arex.inst.runtime.log.LogManager;
import io.arex.inst.runtime.serializer.Serializer;
import io.arex.inst.runtime.util.MockUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Record/replay for @Value and @ConfigurationProperties bean state (Part B of the Spring
 * configuration design) via reflective field overwrite, guarded by a per-bean lock - not the
 * scoped-proxy/clone mechanism originally planned (see project discussion): a CGLIB-style proxy
 * only intercepts method calls, and both @Value fields and @ConfigurationProperties beans are
 * commonly read via direct field access from other beans (no getters), which a proxy cannot see
 * at all. Reflectively mutating the real shared instance's fields works regardless of how the
 * field is later read, at the cost of serializing replay execution for beans actually being
 * overridden - acceptable for replay/test traffic, and narrowed to per-bean (not one global)
 * locks so unrelated beans don't contend with each other.
 *
 * <p>Reuses MockCategoryType.CONFIG_FILE (same category as Part A and Apollo). All fields for
 * the whole app are recorded/replayed as ONE aggregate document per request rather than one
 * document per field: the field set is fixed and known upfront (built once by
 * SpringBeanConfigRegistry), so there is no reason to pay a separate network round-trip per
 * field. This matters most on the replay side, since applyReplayOverrides() runs synchronously
 * before the request handler - one round-trip instead of one-per-field directly bounds how much
 * latency this feature can add to a replayed request, regardless of how many @Value/
 * @ConfigurationProperties fields the app has (a real target app may have ~100+). Per-field
 * fail-open is preserved: the aggregate is just a key-&gt;value map, so a field missing from an
 * older recording still falls back to its local value individually.
 */
public class SpringBeanConfigExtractor {

    private static final String BEAN_KEY_PREFIX = "spring-config-properties:";
    private static final String AGGREGATE_KEY = "spring-config-properties";
    private static final ConcurrentHashMap<String, ReentrantLock> BEAN_LOCKS = new ConcurrentHashMap<>();

    private SpringBeanConfigExtractor() {
    }

    /**
     * Called on entry to the request-wrapping method (DispatcherServlet#doDispatch). If
     * replaying, fetches the whole recorded field map in one call, then acquires this request's
     * needed per-bean locks (in a consistent order to avoid deadlock against another
     * concurrently-replaying request), overwrites fields with recorded values where present
     * (fail-open per field otherwise), and returns an opaque token to pass to
     * {@link #restore(Object)} - guaranteed to run via {@code onThrowable = Throwable.class} on
     * the same advice, regardless of what the request handler does. Returns null when there is
     * nothing to restore (not replaying, or no overrides were actually applied).
     */
    public static Object applyReplayOverrides() {
        if (!ContextManager.needReplay()) {
            return null;
        }
        Map<String, Object> instances = SpringBeanConfigRegistry.beanInstances();
        if (instances.isEmpty()) {
            return null;
        }
        Map<String, String> recorded = replayAggregate();
        if (recorded.isEmpty()) {
            LogManager.info("spring.bean.config.replay.miss",
                    "no recorded @Value/@ConfigurationProperties data found for this case");
            return null;
        }

        List<String> beanNames = new ArrayList<>(instances.keySet());
        Collections.sort(beanNames); // fixed lock ordering across all callers - avoids deadlock

        List<ReentrantLock> acquiredLocks = new ArrayList<>(beanNames.size());
        Map<String, Map<Field, Object>> originalValuesByBean = new LinkedHashMap<>();
        try {
            for (String beanName : beanNames) {
                ReentrantLock lock = BEAN_LOCKS.computeIfAbsent(beanName, name -> new ReentrantLock());
                lock.lock();
                acquiredLocks.add(lock);

                Object bean = instances.get(beanName);
                List<Field> fields = SpringBeanConfigRegistry.beanFields().get(beanName);
                Map<Field, Object> originalValues = null;
                for (Field field : fields) {
                    String raw = recorded.get(fieldKey(beanName, field));
                    if (raw == null) {
                        continue; // fail-open: no recording for this field, leave it untouched
                    }
                    // Isolated per field: a bad write for one field (e.g. a type mismatch from
                    // deserialization, or a record's final field rejecting reflective mutation
                    // entirely) must not abort replay for every other field/bean in this request.
                    try {
                        Object replayedValue = Serializer.deserializeWithType(raw);
                        Object original = getFieldValue(field, bean);
                        setFieldValue(field, bean, replayedValue);
                        if (originalValues == null) {
                            originalValues = new LinkedHashMap<>();
                        }
                        originalValues.put(field, original);
                    } catch (Throwable t) {
                        LogManager.warn("spring.bean.config.field.replay.error",
                                new IllegalStateException("failed to replay " + beanName + "." + field.getName(), t));
                    }
                }
                if (originalValues != null) {
                    originalValuesByBean.put(beanName, originalValues);
                }
            }
        } catch (Throwable t) {
            LogManager.warn("spring.bean.config.replay.error", t);
            restoreAndUnlock(originalValuesByBean, instances, acquiredLocks);
            return null;
        }

        if (originalValuesByBean.isEmpty()) {
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
            LogManager.info("spring.bean.config.replay.miss",
                    "recorded data found but matched 0 registered fields");
            return null;
        }
        int fieldCount = originalValuesByBean.values().stream().mapToInt(Map::size).sum();
        LogManager.info("spring.bean.config.replay.apply",
                "overrode " + fieldCount + " field(s) across " + originalValuesByBean.size()
                        + " bean(s): " + originalValuesByBean.keySet());
        return new RestoreToken(originalValuesByBean, acquiredLocks);
    }

    /** Restores whatever {@link #applyReplayOverrides()} changed and releases its locks. */
    public static void restore(Object token) {
        if (!(token instanceof RestoreToken)) {
            return;
        }
        RestoreToken restoreToken = (RestoreToken) token;
        restoreAndUnlock(restoreToken.originalValuesByBean, SpringBeanConfigRegistry.beanInstances(), restoreToken.locks);
        LogManager.info("spring.bean.config.replay.restore",
                "restored original values for " + restoreToken.originalValuesByBean.size() + " bean(s)");
    }

    private static void restoreAndUnlock(Map<String, Map<Field, Object>> originalValuesByBean,
                                          Map<String, Object> instances, List<ReentrantLock> locks) {
        for (Map.Entry<String, Map<Field, Object>> beanEntry : originalValuesByBean.entrySet()) {
            Object bean = instances.get(beanEntry.getKey());
            if (bean == null) {
                continue;
            }
            for (Map.Entry<Field, Object> fieldEntry : beanEntry.getValue().entrySet()) {
                setFieldValue(fieldEntry.getKey(), bean, fieldEntry.getValue());
            }
        }
        for (ReentrantLock lock : locks) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Called on exit from the request-wrapping method, regardless of outcome. Captures every
     * registered bean's current field values into one map and records it as a single mocker.
     * Values are invariant per JVM lifetime (bound once at startup, no live-refresh support -
     * see project discussion), so this is pure, harmless redundant work on every record-mode
     * request rather than a correctness concern - it costs one network call per request
     * regardless of field count, not one per field.
     */
    public static void captureAndRecord() {
        if (!ContextManager.needRecord()) {
            return;
        }
        Map<String, String> allValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : SpringBeanConfigRegistry.beanInstances().entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            for (Field field : SpringBeanConfigRegistry.beanFields().get(beanName)) {
                Object value = getFieldValue(field, bean);
                if (value == null) {
                    continue;
                }
                allValues.put(fieldKey(beanName, field), Serializer.serializeWithType(value));
            }
        }
        if (allValues.isEmpty()) {
            return;
        }
        Mocker mocker = MockUtils.createConfigFile(AGGREGATE_KEY);
        mocker.getTargetResponse().setBody(Serializer.serialize(allValues));
        mocker.getTargetResponse().setType(allValues.getClass().getName());
        MockUtils.recordMocker(mocker);
        LogManager.info("spring.bean.config.record",
                "recorded " + allValues.size() + " field(s) across "
                        + SpringBeanConfigRegistry.beanInstances().size() + " bean(s)");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> replayAggregate() {
        Mocker mocker = MockUtils.createConfigFile(AGGREGATE_KEY);
        mocker.setRecordEnvironment(1); // required by the storage service's ConfigFile query filter
        Object body = MockUtils.replayBody(mocker);
        if (body instanceof Map) {
            return (Map<String, String>) body;
        }
        return Collections.emptyMap();
    }

    private static String fieldKey(String beanName, Field field) {
        return BEAN_KEY_PREFIX + beanName + "." + field.getName();
    }

    private static Object getFieldValue(Field field, Object bean) {
        try {
            return field.get(bean);
        } catch (IllegalAccessException e) {
            LogManager.warn("spring.bean.config.field.read", e);
            return null;
        }
    }

    private static void setFieldValue(Field field, Object bean, Object value) {
        try {
            field.set(bean, value);
        } catch (IllegalAccessException e) {
            LogManager.warn("spring.bean.config.field.write", e);
        }
    }

    private static final class RestoreToken {
        final Map<String, Map<Field, Object>> originalValuesByBean;
        final List<ReentrantLock> locks;

        RestoreToken(Map<String, Map<Field, Object>> originalValuesByBean, List<ReentrantLock> locks) {
            this.originalValuesByBean = originalValuesByBean;
            this.locks = locks;
        }
    }
}
