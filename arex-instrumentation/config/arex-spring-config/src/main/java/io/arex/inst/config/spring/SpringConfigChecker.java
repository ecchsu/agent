package io.arex.inst.config.spring;

import io.arex.agent.bootstrap.constants.ConfigConstants;

public class SpringConfigChecker {

    private SpringConfigChecker() {
    }

    /**
     * Master opt-in gate for the whole spring-config module. Off by default: when this
     * returns true, the module installs no instrumentation at all (see
     * SpringConfigModuleInstrumentation#instrumentationTypes), so a business application
     * running without the flag is untouched by this feature.
     *
     * <p>Deliberately reads the JVM system property directly, the same way
     * ConfigConstants.DISABLE_MODULE is read in ConfigManager#init(), rather than going through
     * Config.get()/ConfigManager's remote-config-synced properties map: instrumentationTypes()
     * is evaluated once at agent premain, before any remote config fetch can possibly have
     * completed, so Config.get()'s properties map does not yet (and for this flag, never will)
     * contain this key. This flag is inherently a local, JVM-startup-time decision, not a
     * dynamically-refreshable one.
     */
    public static boolean disabled() {
        return !Boolean.parseBoolean(System.getProperty(ConfigConstants.ENABLE_SPRING_CONFIG));
    }
}
