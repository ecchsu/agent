package io.arex.inst.config.spring;

import io.arex.inst.extension.MethodInstrumentation;
import io.arex.inst.extension.TypeInstrumentation;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Hooks the same SpringApplication.run(String...) exit point arex-component-scan already uses,
 * to build the SpringBeanConfigRegistry exactly once, right after the context (and therefore
 * every non-lazy singleton bean, including @Value/@ConfigurationProperties-carrying ones) has
 * finished initializing.
 */
public class SpringApplicationRunInstrumentation extends TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("org.springframework.boot.SpringApplication");
    }

    @Override
    public List<MethodInstrumentation> methodAdvices() {
        ElementMatcher<MethodDescription> matcher = isMethod()
                .and(named("run"))
                .and(takesArguments(1))
                .and(not(isStatic()));
        return singletonList(new MethodInstrumentation(matcher, SpringRunAdvice.class.getName()));
    }

    public static class SpringRunAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Return ConfigurableApplicationContext context) {
            SpringBeanConfigRegistry.initialize(context);
        }
    }
}
