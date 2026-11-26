package io.sensible.studio.translation.impl;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * One secret of the instance, such as a model API key: a factory configuration ("Sensible Studio Secret") kept by the OSGi
 * configuration admin in the instance folder, outside the repository. Content refers to it as
 * {@code $[secret:NAME]}; its value is never answered over HTTP.
 */
@Component(service = SecretEntry.class, configurationPolicy = ConfigurationPolicy.REQUIRE,
        configurationPid = "io.sensible.studio.translation.impl.SecretEntry")
@Designate(ocd = SecretEntry.Config.class, factory = true)
public class SecretEntry {

    @ObjectClassDefinition(name = "Sensible Studio Secret",
            description = "A secret such as an API key, referred to in cloud configurations as $[secret:NAME]. Stays out of the repository.")
    public @interface Config {
        @AttributeDefinition(name = "Name", description = "Letters, digits and '_', e.g. ANTHROPIC_API_KEY")
        String name();

        @AttributeDefinition(name = "Value", type = AttributeType.PASSWORD)
        String value();

        String webconsole_configurationFactory_nameHint() default "{name}";
    }

    private volatile String name;
    private volatile String value;

    @Activate
    @Modified
    void activate(Config config) {
        name = config.name() == null ? "" : config.name().trim();
        value = config.value();
    }

    String name() {
        return name;
    }

    String value() {
        return value;
    }
}
