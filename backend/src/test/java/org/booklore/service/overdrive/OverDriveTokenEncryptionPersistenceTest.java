package org.booklore.service.overdrive;

import jakarta.persistence.EntityManager;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.OverDriveTokenEntity;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The converter as Hibernate actually applies it, with a key configured. */
@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "app.overdrive.credential-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:tokenenc;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
@Import(OverDriveTokenEncryptionPersistenceTest.TestConfig.class)
class OverDriveTokenEncryptionPersistenceTest {

    @TestConfiguration
    static class TestConfig {
        @Bean("flyway")
        @Primary
        Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJjaGlwIjoiMSJ9.sig";

    @Autowired
    private EntityManager entityManager;

    @Test
    void aTokenIsStoredEncryptedAndReadsBackPlain() {
        OverDriveTokenEntity row = OverDriveTokenEntity.builder().userId(1L).identity("card-1").token(JWT).build();
        entityManager.persist(row);
        entityManager.flush();
        entityManager.clear();

        String raw = (String) entityManager.createNativeQuery("SELECT token FROM overdrive_token WHERE id = :id")
                .setParameter("id", row.getId()).getSingleResult();
        assertThat(raw).startsWith(OverDriveTokenConverter.PREFIX).doesNotContain(JWT);
        assertThat(entityManager.find(OverDriveTokenEntity.class, row.getId()).getToken()).isEqualTo(JWT);
    }
}
