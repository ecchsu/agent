package io.arex.inst.config.spring;

import io.arex.agent.bootstrap.model.Mocker;
import io.arex.agent.bootstrap.util.StringUtil;
import io.arex.inst.runtime.context.ArexContext;
import io.arex.inst.runtime.context.ContextManager;
import io.arex.inst.runtime.log.LogManager;
import io.arex.inst.runtime.serializer.Serializer;
import io.arex.inst.runtime.util.MockUtils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Record/replay for Spring's dynamic {@code Environment}/{@code PropertySourcesPropertyResolver}
 * reads (Part A of the Spring configuration design). Deliberately does NOT override recordId/
 * replayId the way Apollo's config extractor does - MockUtils.createConfigFile(...) is left to
 * inherit them from ContextManager.currentContext() as-is, which is what makes this naturally
 * per-request and safe across concurrently-replaying cases from different origin environments.
 *
 * <p>Reuses the existing MockCategoryType.CONFIG_FILE category (shared with Apollo's config
 * recording) rather than introducing a new one; a fixed "spring-config-env" operationName keeps
 * this producer's one aggregate document distinct from Apollo's namespace-named documents in
 * that shared key space.
 *
 * <p>Unlike a request's dynamic reads count, which is discovered incrementally as
 * getProperty(...) calls happen (rather than known upfront like Part B's fixed field set), all
 * keys read during one request are still recorded/replayed as ONE aggregate document per
 * request rather than one per key: reads are buffered in the request's own ArexContext
 * attachment and flushed once at request exit (see SpringConfigServletV3/V5RequestHandler); on
 * replay, the first getProperty() call in a request fetches the whole map in one round-trip and
 * caches it on the same attachment, so every later getProperty() call in that request is a pure
 * in-memory lookup. This bounds both record-side network volume and (more importantly, since the
 * replay lookup runs inline and can add latency to the replaying request) replay-side round-trips
 * to one per request, regardless of how many distinct keys that request happens to read.
 */
public class SpringConfigExtractor {

    private static final String AGGREGATE_KEY = "spring-config-env";
    private static final String REPLAY_CACHE_ATTACHMENT = "springConfigReplayCache";
    private static final String RECORD_BUFFER_ATTACHMENT = "springConfigRecordBuffer";
    private static final ThreadLocal<Boolean> IN_PROGRESS = new ThreadLocal<>();

    private SpringConfigExtractor() {
    }

    /**
     * Called on method enter. Returns the recorded value to short-circuit the real resolution,
     * or null to let it proceed normally (not replaying, no recorded value, or a guard tripped).
     */
    public static Object tryReplay(String key) {
        if (StringUtil.isEmpty(key) || reentrant() || !ContextManager.needReplay()) {
            return null;
        }
        ArexContext context = ContextManager.currentContext();
        if (context == null) {
            return null;
        }
        IN_PROGRESS.set(Boolean.TRUE);
        try {
            Map<String, String> recorded = replayAggregate(context);
            String raw = recorded.get(key);
            if (raw == null) {
                // fail-open (decided default): caller falls back to real resolution
                LogManager.info("spring.config.replay.miss", key);
                return null;
            }
            return Serializer.deserializeWithType(raw);
        } finally {
            IN_PROGRESS.remove();
        }
    }

    /**
     * Called on method exit with the real resolved value, when replay didn't already
     * short-circuit this call. Buffers into the current request's ArexContext rather than
     * recording immediately - see {@link #flushRecordBuffer()}.
     */
    public static void tryRecord(String key, Object resolvedValue) {
        if (StringUtil.isEmpty(key) || resolvedValue == null || reentrant() || !ContextManager.needRecord()) {
            return;
        }
        ArexContext context = ContextManager.currentContext();
        if (context == null) {
            return;
        }
        recordBuffer(context).put(key, Serializer.serializeWithType(resolvedValue));
    }

    /**
     * Called once per request, at request exit (see SpringConfigServletV3/V5RequestHandler),
     * to flush whatever {@link #tryRecord} accumulated as a single aggregate mocker. A no-op if
     * nothing was buffered (e.g. this request made no dynamic Environment reads at all).
     */
    public static void flushRecordBuffer() {
        if (SpringConfigChecker.disabled()) {
            return;
        }
        ArexContext context = ContextManager.currentContext();
        if (context == null) {
            return;
        }
        Object buffered = context.getAttachment(RECORD_BUFFER_ATTACHMENT);
        if (!(buffered instanceof Map) || ((Map<?, ?>) buffered).isEmpty()) {
            return;
        }
        Mocker mocker = MockUtils.createConfigFile(AGGREGATE_KEY);
        mocker.getTargetResponse().setBody(Serializer.serialize(buffered));
        mocker.getTargetResponse().setType(buffered.getClass().getName());
        MockUtils.recordMocker(mocker);
        LogManager.info("spring.config.env.record.flush",
                "recorded " + ((Map<?, ?>) buffered).size() + " dynamic key(s) for recordId="
                        + context.getCaseId() + ": " + ((Map<?, ?>) buffered).keySet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> replayAggregate(ArexContext context) {
        Object cached = context.getAttachment(REPLAY_CACHE_ATTACHMENT);
        if (cached instanceof Map) {
            return (Map<String, String>) cached;
        }
        Mocker mocker = MockUtils.createConfigFile(AGGREGATE_KEY);
        // required: the storage service's ConfigFile query path filters on this field
        // (see AREXMockerMongoRepositoryProvider#buildRecordFilters); Apollo's own
        // extractor sets the same value for the same reason.
        mocker.setRecordEnvironment(1);
        Object body = MockUtils.replayBody(mocker);
        Map<String, String> result = (body instanceof Map) ? (Map<String, String>) body : Collections.emptyMap();
        LogManager.info("spring.config.env.replay.fetch",
                "fetched aggregate for recordId=" + context.getCaseId() + ": " + result.size()
                        + " key(s) found" + (result.isEmpty() ? "" : ": " + result.keySet()));
        context.setAttachment(REPLAY_CACHE_ATTACHMENT, result); // cache even a miss, so we fetch at most once
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> recordBuffer(ArexContext context) {
        Object existing = context.getAttachment(RECORD_BUFFER_ATTACHMENT);
        if (existing instanceof Map) {
            return (Map<String, String>) existing;
        }
        Map<String, String> buffer = new ConcurrentHashMap<>();
        context.setAttachment(RECORD_BUFFER_ATTACHMENT, buffer);
        return buffer;
    }

    /**
     * Guards against AREX's own internal machinery (storage client, serializer, ...)
     * recursively triggering another getProperty call while we're already mid-record/replay
     * for a different key on this thread.
     */
    private static boolean reentrant() {
        return Boolean.TRUE.equals(IN_PROGRESS.get());
    }
}
