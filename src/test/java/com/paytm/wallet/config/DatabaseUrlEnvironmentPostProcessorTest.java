package com.paytm.wallet.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deployment-critical and impossible to check locally without a managed database, so it
 * is pinned here instead: getting this wrong means the app boots fine on a laptop and
 * fails to reach Postgres on the host.
 */
class DatabaseUrlEnvironmentPostProcessorTest {

    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    private MockEnvironment environmentWith(String databaseUrl) {
        MockEnvironment environment = new MockEnvironment();
        if (databaseUrl != null) {
            environment.setProperty("DATABASE_URL", databaseUrl);
        }
        processor.postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void translatesAProviderUrlIntoJdbcPropertiesAndForcesTls() {
        MockEnvironment environment = environmentWith("postgres://wallet_user:s3cret@db.example.com:5432/wallet");

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/wallet?sslmode=require");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("wallet_user");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("s3cret");
    }

    @Test
    void keepsAnSslmodeTheProviderAlreadyChose() {
        MockEnvironment environment = environmentWith("postgresql://u:p@host/db?sslmode=verify-full");

        assertThat(environment.getProperty("spring.datasource.url")).endsWith("sslmode=verify-full");
        assertThat(environment.getProperty("spring.datasource.url")).doesNotContain("sslmode=require");
    }

    @Test
    void defaultsThePortWhenTheProviderOmitsIt() {
        MockEnvironment environment = environmentWith("postgres://u:p@host/db");
        assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:postgresql://host:5432/db");
    }

    @Test
    void passesThroughAUrlThatIsAlreadyJdbc() {
        MockEnvironment environment = environmentWith("jdbc:postgresql://host:5432/db?sslmode=require");

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://host:5432/db?sslmode=require");
        assertThat(environment.getProperty("spring.datasource.username")).isNull();
    }

    @Test
    void leavesLocalDevelopmentAloneWhenDatabaseUrlIsUnset() {
        assertThat(environmentWith(null).getProperty("spring.datasource.url")).isNull();
    }
}