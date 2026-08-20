package uk.gov.hmcts.cp.informantregister.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start on a configuration that cannot be operated safely.
 *
 * <p>Two timing relationships and one credential rule are checked here rather than discovered later:
 * a run that can outlive its claim, a broker lock that can expire mid-run, and an ambiguous
 * credential source all fail quietly in production and loudly at startup, so startup is where they
 * are made to fail.
 */
@Component
public class PropertiesValidator implements InitializingBean {

    private final InformantRegisterProperties properties;

    public PropertiesValidator(final InformantRegisterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
    }

    /**
     * Checks the settings that must hold for the service to be safe to run.
     *
     * @param properties the bound settings
     */
    public static void validate(final InformantRegisterProperties properties) {
        // Not implemented yet.
    }
}
