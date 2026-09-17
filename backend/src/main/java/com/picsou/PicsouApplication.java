package com.picsou;

import com.picsou.config.TimeZoneConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
public class PicsouApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PicsouApplication.class);
        // Pins the JVM default zone from `app.timezone` before any bean is created — see
        // TimeZoneConfig for why this cannot be a component-scanned @PostConstruct.
        application.addListeners(new TimeZoneConfig());
        application.run(args);
    }
}
