package com.ridex.platform.db;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs before the application connects to its database: if ridex.database.auto-create=true, create the service's own
 * database when it does not exist yet. Registered through META-INF/spring.factories, so every service gets it for free.
 */
public class DatabaseAutoCreateListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        if (!"true".equals(env.getProperty("ridex.database.auto-create"))) {
            return;
        }
        String url = env.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            return;
        }
        DatabaseBootstrap.ensureDatabaseExists(url, env.getProperty("spring.datasource.username", ""),
                env.getProperty("spring.datasource.password", ""));
    }
}
