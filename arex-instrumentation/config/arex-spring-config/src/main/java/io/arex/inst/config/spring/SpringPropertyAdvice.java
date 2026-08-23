package io.arex.inst.config.spring;

import net.bytebuddy.asm.Advice;

/**
 * Shared advice body for both {@code org.springframework.core.env.PropertySourcesPropertyResolver}
 * and {@code org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertyResolver}
 * (see SpringBootConfigurationPropertyResolverInstrumentation for why the latter also needs
 * hooking: Spring Boot's ApplicationServletEnvironment overrides createPropertyResolver() to
 * return the latter instead of the former, so a plain Environment.getProperty() call on a Boot
 * app's main environment never reaches PropertySourcesPropertyResolver at all). Both classes
 * declare a method shaped exactly like {@code getProperty(String, Class, boolean)} with an
 * erased Object return type, so one advice works for both regardless of the declaring class or
 * that class's method's visibility (ByteBuddy advises methods directly at the bytecode level -
 * declared visibility, including private, doesn't matter).
 */
public class SpringPropertyAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static Object onEnter(@Advice.Argument(0) String key) {
        return SpringConfigExtractor.tryReplay(key);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Enter Object replayed,
                               @Advice.Argument(0) String key,
                               @Advice.Return(readOnly = false) Object result) {
        if (replayed != null) {
            result = replayed;
            return;
        }
        SpringConfigExtractor.tryRecord(key, result);
    }
}
