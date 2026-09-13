package com.paytm.wallet.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;


public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "databaseUrl";
    private static final String ENV_VARIABLE = "DATABASE_URL";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty(ENV_VARIABLE);
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }

        Map<String, Object> properties = new HashMap<>();
        if (databaseUrl.startsWith("jdbc:")) {
            properties.put("spring.datasource.url", databaseUrl);
        } else {
            properties.putAll(parseLibpqUrl(databaseUrl));
        }

        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }

    private Map<String, Object> parseLibpqUrl(String databaseUrl) {
        URI uri;
        try {
            uri = new URI(databaseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(ENV_VARIABLE + " is set but is not a valid URL", e);
        }

        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getQuery() == null ? "" : uri.getQuery();
        if (!query.contains("sslmode=")) {
            query = query.isEmpty() ? "sslmode=require" : query + "&sslmode=require";
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url",
                "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query);

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int separator = userInfo.indexOf(':');
            properties.put("spring.datasource.username", separator < 0 ? userInfo : userInfo.substring(0, separator));
            if (separator >= 0) {
                properties.put("spring.datasource.password", userInfo.substring(separator + 1));
            }
        }
        return properties;
    }
}
