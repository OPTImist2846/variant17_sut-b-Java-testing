package ua.course.rental;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestMethodOrder(MethodOrderer.Random.class)
@DisplayName("Testcontainers: PostgreSQL + WireMock")
class RentalContainersIT {
    static final String POLICY_PATH = "/rental-policy";
    static final int WIREMOCK_PORT = 8080;
    static final Duration POLICY_TIMEOUT = Duration.ofSeconds(2);
    static final long STANDARD_RATE = 10000;
    static final long PREMIUM_THRESHOLD_RATE = 50000;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Container
    static final GenericContainer<?> wiremock = new GenericContainer<>("wiremock/wiremock:3.13.1")
            .withExposedPorts(WIREMOCK_PORT)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    static JdbcTemplate jdbc;
    static WireMock wireMockAdmin;
    static URI policyBaseUrl;

    EquipmentRepository equipment;
    RentalRepository rentals;
    RentalService service;

    @BeforeAll
    static void prepareEnvironment() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .executeInTransaction(false)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        wireMockAdmin = new WireMock(wiremock.getHost(), wiremock.getMappedPort(WIREMOCK_PORT));
        policyBaseUrl = URI.create("http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(WIREMOCK_PORT));
    }

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE rental_items, rentals, equipment RESTART IDENTITY");
        wireMockAdmin.resetMappings();
        wireMockAdmin.resetRequests();
        equipment = new EquipmentRepository(jdbc);
        rentals = new RentalRepository(jdbc);
        service = new RentalService(equipment, rentals, new HttpRentalPolicy(policyBaseUrl, POLICY_TIMEOUT));
    }

    Equipment saveEquipment(String code, long dailyRate, int stock) throws Exception {
        return equipment.create(new Equipment(0, code, dailyRate, stock));
    }

    void policyResponds(ResponseDefinitionBuilder response) {
        wireMockAdmin.register(policyRequest().willReturn(response));
    }

    MappingBuilder policyRequest() {
        return post(urlEqualTo(POLICY_PATH));
    }

    RequestPatternBuilder policyCall(Equipment item, int quantity, int days) {
        return postRequestedFor(urlEqualTo(POLICY_PATH))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(
                        "{\"equipmentId\":" + item.id() + ",\"quantity\":" + quantity + ",\"days\":" + days + "}"));
    }

    int rowCount(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    int httpRequestCount() {
        return wireMockAdmin.find(anyRequestedFor(anyUrl())).size();
    }

    @Test
    @DisplayName("Середовище: БД із контейнера на динамічному порту, міграції застосовані автоматично")
    void environment_usesRuntimeContainerParameters_andAppliedMigrations() {
        int mappedPort = postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);

        assertTrue(postgres.getJdbcUrl().contains(":" + mappedPort + "/"), postgres.getJdbcUrl());
        assertNotEquals(5432, mappedPort);
        assertNotEquals(WIREMOCK_PORT, wiremock.getMappedPort(WIREMOCK_PORT));
        assertEquals(List.of("equipment", "rental_items", "rentals"), jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public' "
                        + "AND table_name <> 'flyway_schema_history' ORDER BY table_name", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success", Integer.class));
    }

    @Test
    @DisplayName("Позитивний сценарій: WireMock дозволяє оренду, запис збережено в PostgreSQL")
    void externalApproval_createsRental_andCallsPolicyOnce() throws Exception {
        var item = saveEquipment("CAM-01", STANDARD_RATE, 5);
        policyResponds(okJson("{\"allowed\":true}"));

        var result = service.rent(new RentalRequest("RENT-OK", item.id(), 2, 3, false));

        var stored = rentals.findById(result.id());
        assertNotNull(stored);
        assertEquals(result, stored);
        assertAll(
                () -> assertEquals("RENT-OK", stored.reference()),
                () -> assertEquals(60000, stored.total()),
                () -> assertEquals(0, stored.lateFee()),
                () -> assertEquals(RentalStatus.Active, stored.status()),
                () -> assertEquals(item.id(), stored.item().equipmentId()),
                () -> assertEquals(2, stored.item().quantity()),
                () -> assertEquals(STANDARD_RATE, stored.item().dailyRate()));
        assertEquals(1, rowCount("rentals"));
        wireMockAdmin.verifyThat(1, policyCall(item, 2, 3));
        assertEquals(1, httpRequestCount());
    }

    @Test
    @DisplayName("Персональне правило: преміальний клієнт, 500 грн/доба, 5 одиниць — оренда збережена")
    void premiumThresholdRental_appliesPersonalRule_andCallsPolicy() throws Exception {
        var item = saveEquipment("PRO-CAM-01", PREMIUM_THRESHOLD_RATE, 10);
        policyResponds(okJson("{\"allowed\":true}"));

        var result = service.rent(new RentalRequest("RENT-PREMIUM", item.id(), 5, 2, true));

        var stored = rentals.findById(result.id());
        assertAll(
                () -> assertTrue(stored.premium()),
                () -> assertEquals(500000, stored.total()),
                () -> assertEquals(5, stored.item().quantity()),
                () -> assertEquals(PREMIUM_THRESHOLD_RATE, stored.item().dailyRate()),
                () -> assertEquals(RentalStatus.Active, stored.status()));
        assertEquals(List.of(result.id()),
                rentals.findRentals(item.id(), RentalStatus.Active).stream().map(RentalOrder::id).toList());
        wireMockAdmin.verifyThat(1, policyCall(item, 5, 2));
    }

    @Test
    @DisplayName("Зовнішня бізнес-відмова: allowed=false — запис не створено, HTTP-запит = 1")
    void externalRejection_leavesDatabaseUnchanged_afterOneRequest() throws Exception {
        var item = saveEquipment("CAM-02", STANDARD_RATE, 5);
        policyResponds(okJson("{\"allowed\":false}"));

        var error = assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-DENIED", item.id(), 1, 4, false)));

        assertEquals("Оренду не дозволено", error.getMessage());
        assertEquals(0, rowCount("rentals"));
        assertEquals(0, rowCount("rental_items"));
        assertTrue(rentals.findRentals(item.id(), RentalStatus.Active).isEmpty());
        wireMockAdmin.verifyThat(1, policyCall(item, 1, 4));
        assertEquals(1, httpRequestCount());
    }

    @Test
    @DisplayName("Недоступність сервісу: 503 — очікувана помилка, запис не створено")
    void serviceUnavailable_failsOperation_andKeepsDatabaseClean() throws Exception {
        var item = saveEquipment("CAM-03", STANDARD_RATE, 5);
        policyResponds(aResponse().withStatus(503).withBody("Service Unavailable"));

        var error = assertThrows(IllegalStateException.class,
                () -> service.rent(new RentalRequest("RENT-503", item.id(), 1, 1, false)));

        assertEquals("Помилка HTTP-політики: 503", error.getMessage());
        assertEquals(0, rowCount("rentals"));
        assertEquals(new Equipment(item.id(), "CAM-03", STANDARD_RATE, 5), equipment.findById(item.id()));
        wireMockAdmin.verifyThat(1, policyCall(item, 1, 1));
    }

    @Test
    @DisplayName("Некоректна відповідь сервісу: 200 без поля allowed — оренда не створюється")
    void malformedPolicyResponse_isRejected_withoutRecord() throws Exception {
        var item = saveEquipment("CAM-04", STANDARD_RATE, 5);
        policyResponds(okJson("{\"status\":\"ok\"}"));

        var error = assertThrows(IllegalStateException.class,
                () -> service.rent(new RentalRequest("RENT-MALFORMED", item.id(), 1, 1, false)));

        assertEquals("Некоректна відповідь HTTP-політики", error.getMessage());
        assertEquals(0, rowCount("rentals"));
        wireMockAdmin.verifyThat(1, policyCall(item, 1, 1));
    }

    @Test
    @DisplayName("Таймаут сервісу: відповідь довша за таймаут — оренда не створюється")
    void slowPolicyResponse_timesOut_withoutRecord() throws Exception {
        var item = saveEquipment("CAM-05", STANDARD_RATE, 5);
        policyResponds(okJson("{\"allowed\":true}").withFixedDelay((int) POLICY_TIMEOUT.toMillis() + 1500));

        assertThrows(IllegalStateException.class,
                () -> service.rent(new RentalRequest("RENT-TIMEOUT", item.id(), 1, 1, false)));

        assertEquals(0, rowCount("rentals"));
        wireMockAdmin.verifyThat(1, policyCall(item, 1, 1));
    }

    @Test
    @DisplayName("Локальна відмова за персональним правилом: звичайний клієнт від 500 грн — HTTP-запит = 0")
    void personalRuleRejection_happensBeforeHttpCall() throws Exception {
        var item = saveEquipment("PRO-CAM-02", PREMIUM_THRESHOLD_RATE, 10);
        policyResponds(okJson("{\"allowed\":true}"));

        var error = assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-REGULAR", item.id(), 1, 1, false)));

        assertEquals("Персональні умови оренди не виконані", error.getMessage());
        assertEquals(0, rowCount("rentals"));
        wireMockAdmin.verifyThat(0, postRequestedFor(urlEqualTo(POLICY_PATH)));
        assertEquals(0, httpRequestCount());
    }

    @Test
    @DisplayName("Локальна відмова: преміальний клієнт бере 6 дорогих одиниць — HTTP-запит = 0")
    void premiumOverFiveUnits_isRejectedLocally() throws Exception {
        var item = saveEquipment("PRO-CAM-03", 80000, 10);
        policyResponds(okJson("{\"allowed\":true}"));

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-PREMIUM-6", item.id(), 6, 1, true)));

        assertEquals(0, rowCount("rentals"));
        assertEquals(0, httpRequestCount());
    }

    @Test
    @DisplayName("Локальна відмова: нестача обладнання — HTTP-запит = 0")
    void insufficientStock_isRejectedLocally() throws Exception {
        var item = saveEquipment("CAM-06", STANDARD_RATE, 2);
        policyResponds(okJson("{\"allowed\":true}"));

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-STOCK", item.id(), 3, 1, false)));

        assertEquals(0, rowCount("rentals"));
        assertEquals(0, httpRequestCount());
    }

    @Test
    @DisplayName("Повернення оренди змінює статус у БД і не звертається до зовнішнього сервісу")
    void returnRental_updatesStatus_withoutAdditionalHttpCall() throws Exception {
        var item = saveEquipment("CAM-07", STANDARD_RATE, 5);
        policyResponds(okJson("{\"allowed\":true}"));
        var rental = service.rent(new RentalRequest("RENT-RETURN", item.id(), 1, 2, false));

        service.returnRental(rental.id());

        var stored = rentals.findById(rental.id());
        assertEquals(RentalStatus.Returned, stored.status());
        assertEquals(20000, stored.total());
        assertEquals(0, stored.lateFee());
        assertEquals(1, httpRequestCount());
    }
}
