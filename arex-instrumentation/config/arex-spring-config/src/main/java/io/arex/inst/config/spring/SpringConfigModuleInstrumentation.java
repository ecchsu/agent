package io.arex.inst.config.spring;

import com.google.auto.service.AutoService;
import io.arex.inst.extension.ModuleInstrumentation;
import io.arex.inst.extension.TypeInstrumentation;
import io.arex.inst.runtime.log.LogManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Registers both parts of the Spring configuration record/replay feature: Part A (dynamic
 * Environment/PropertySourcesPropertyResolver reads) and Part B (@Value/@ConfigurationProperties
 * bean state, via reflective field overwrite). Gated entirely by {@link SpringConfigChecker}:
 * when the feature flag is off, instrumentationTypes() returns an empty list, and
 * InstrumentationInstaller#installModule skips installing any type/method advice for this
 * module at all - no bytecode of Spring's classes is ever touched, and no application bean
 * definition is ever modified.
 */
@AutoService(ModuleInstrumentation.class)
public class SpringConfigModuleInstrumentation extends ModuleInstrumentation {

    public SpringConfigModuleInstrumentation() {
        super("spring-config");
    }

    @Override
    public List<TypeInstrumentation> instrumentationTypes() {
        if (SpringConfigChecker.disabled()) {
            LogManager.info("spring.config.module",
                    "disabled (arex.spring.config is not \"true\") - no instrumentation installed");
            return Collections.emptyList();
        }
        LogManager.info("spring.config.module",
                "enabled - installing Part A (dynamic Environment reads) and Part B "
                        + "(@Value/@ConfigurationProperties) instrumentation");
        return Arrays.asList(
                // Part A
                new SpringPropertyResolverInstrumentation(),
                new SpringBootConfigurationPropertyResolverInstrumentation(),
                // Part B
                new SpringApplicationRunInstrumentation(),
                new DispatcherServletInstrumentation());
    }
}
