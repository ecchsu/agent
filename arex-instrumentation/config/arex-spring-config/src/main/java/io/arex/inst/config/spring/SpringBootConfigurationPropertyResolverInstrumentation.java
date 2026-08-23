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
 * Hooks the actual convergence point for dynamic property resolution on a Spring Boot
 * application's main Environment: {@code ApplicationServletEnvironment} (and
 * {@code ApplicationEnvironment}) override {@code AbstractEnvironment#createPropertyResolver}
 * to return a {@code ConfigurationPropertySourcesPropertyResolver} instead of a plain
 * {@code PropertySourcesPropertyResolver} - a separate class (extends AbstractPropertyResolver
 * directly) with its own private {@code getProperty(String, Class, boolean)} method of the
 * same shape. Without this, a Boot app's own {@code Environment.getProperty(...)} calls are
 * invisible to SpringPropertyResolverInstrumentation entirely, even though @Value placeholder
 * resolution (a separate, plain-Spring resolver instance) still gets caught by it. Since almost
 * every realistic target application is Spring Boot, this is required, not optional.
 */
public class SpringBootConfigurationPropertyResolverInstrumentation extends TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertyResolver");
    }

    @Override
    public List<MethodInstrumentation> methodAdvices() {
        ElementMatcher<MethodDescription> matcher = named("getProperty")
                .and(takesArguments(3))
                .and(takesArgument(0, named("java.lang.String")));
        return singletonList(new MethodInstrumentation(matcher, SpringPropertyAdvice.class.getName()));
    }
}
