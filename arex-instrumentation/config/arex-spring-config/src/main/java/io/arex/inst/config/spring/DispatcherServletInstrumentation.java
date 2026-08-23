package io.arex.inst.config.spring;

import io.arex.inst.extension.MethodInstrumentation;
import io.arex.inst.extension.TypeInstrumentation;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Brackets Spring MVC's whole per-request handling (routing, interceptors, the controller
 * method call, view rendering) with apply-on-enter / restore-on-exit for Part B's field
 * overwrites. {@code onThrowable = Throwable.class} on the exit advice guarantees restoration
 * runs even if the request handler throws - without this, a single failing replayed request
 * could leave shared bean state permanently overwritten for every later request on the host.
 */
public class DispatcherServletInstrumentation extends TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("org.springframework.web.servlet.DispatcherServlet");
    }

    @Override
    public List<MethodInstrumentation> methodAdvices() {
        ElementMatcher<MethodDescription> matcher = named("doDispatch").and(takesArguments(2));
        return singletonList(new MethodInstrumentation(matcher, DispatchAdvice.class.getName()));
    }

    public static class DispatchAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object onEnter() {
            return SpringBeanConfigExtractor.applyReplayOverrides();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Enter Object restoreToken) {
            SpringBeanConfigExtractor.restore(restoreToken);
            SpringBeanConfigExtractor.captureAndRecord();
        }
    }
}
