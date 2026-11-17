package ua.course.rental;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

abstract class IntegrationTestSupport {
    static final String TEST_DATABASE = "rental_variant17_test";

    static Connection connection;
    static JdbcTemplate jdbc;

    EquipmentRepository equipment;
    RentalRepository rentals;

    @BeforeAll
    static void openConnection() throws SQLException {
        connection = Database.connect();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        cleanDatabase();
        connection.close();
    }

    @BeforeEach
    void prepareCleanState() {
        cleanDatabase();
        equipment = new EquipmentRepository(connection);
        rentals = new RentalRepository(connection);
    }

    static void cleanDatabase() {
        jdbc.execute("TRUNCATE rental_items, rentals, equipment RESTART IDENTITY");
    }

    Equipment saveEquipment(String code, long dailyRate, int stock) throws SQLException {
        return equipment.create(new Equipment(0, code, dailyRate, stock));
    }

    RentalOrder activeOrder(String reference, Equipment item, int quantity, int days, boolean premium) {
        long total = item.dailyRate() * quantity * days;
        return new RentalOrder(0, reference, days, premium, total, 0, RentalStatus.Active,
                new RentalItem(0, item.id(), quantity, item.dailyRate()));
    }

    int rowCount(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
