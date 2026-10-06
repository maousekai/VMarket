package com.vmarket.order;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:order-migrations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
class OrderPaymentMigrationTest {
    @Test void flywayCreatesSchemaMatchingOrderAndOutboxEntities() {}
}
