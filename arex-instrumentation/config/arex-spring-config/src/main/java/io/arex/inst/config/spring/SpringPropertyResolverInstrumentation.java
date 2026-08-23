package io.arex.inst.config.spring;

import io.arex.inst.extension.MethodInstrumentation;
import io.arex.inst.extension.TypeInstrumentation;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Hooks the convergence point for plain-Spring dynamic property resolution:
 * {@code PropertySourcesPropertyResolver#getProperty(String, Class, boolean)}. This is what
 * {@code PropertySourcesPlaceholderConfigurer} uses internally to resolve {@code @Value}
 * placeholders (its own resolver instance, separate from the environment's), and what a plain
 * (non-Spring-Boot) {@code Environment.getProperty(...)} call resolves through.
 *
 * <p>On a Spring Boot application, the main {@code Environment}'s own getProperty(...) does NOT
 * go through this class - see SpringBootConfigurationPropertyResolverInstrumentation for that
 * (materially different, Boot-specific) case, which is required for realistic target apps.
 */
public class SpringPropertyResolverInstrumentation extends TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("org.springframework.core.env.PropertySourcesPropertyResolver");
    }

    @Override
    public List<MethodInstrumentation> methodAdvices() {
        ElementMatcher<MethodDescription> matcher = named("getProperty")
                .and(takesArguments(3))
                .and(takesArgument(0, named("java.lang.String")));
        return singletonList(new MethodInstrumentation(matcher, SpringPropertyAdvice.class.getName()));
    }
}
