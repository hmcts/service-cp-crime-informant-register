package uk.gov.hmcts.cp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot entry point for the informant-register service. */
@SpringBootApplication
public class Application {

    /** Starts the Spring container. */
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
