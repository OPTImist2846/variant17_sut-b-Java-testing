package ua.course.rental;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataIntegrityViolationException;

@TestMethodOrder(MethodOrderer.Random.class)
@DisplayName("Repository ↔ Database")
class RepositoryIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Тести працюють саме з окремою тестовою БД")
    void usesDedicatedTestDatabase() {
        assertEquals(TEST_DATABASE, jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(0, rowCount("equipment"));
        assertEquals(0, rowCount("rentals"));
    }

    @Test
    @DisplayName("Обладнання зберігається та повторно читається з БД")
    void createEquipment_thenFindById_repositoryState() throws Exception {
        var saved = saveEquipment("CAM-01", 10000, 5);

        var stored = equipment.findById(saved.id());

        assertNotEquals(0, saved.id());
        assertEquals(new Equipment(saved.id(), "CAM-01", 10000, 5), stored);
        assertEquals(1, rowCount("equipment"));
    }

    @Test
    @DisplayName("Оренда зберігається разом із позицією та повторно читається з БД")
    void createRental_thenFindById_repositoryState() throws Exception {
        var item = saveEquipment("DRILL-01", 25000, 4);

        var saved = rentals.create(activeOrder("RENT-REPO-1", item, 2, 3, true));
        var stored = rentals.findById(saved.id());

        assertNotNull(stored);
        assertEquals(saved, stored);
        assertAll(
                () -> assertEquals("RENT-REPO-1", stored.reference()),
                () -> assertEquals(150000, stored.total()),
                () -> assertEquals(RentalStatus.Active, stored.status()),
                () -> assertEquals(item.id(), stored.item().equipmentId()),
                () -> assertEquals(2, stored.item().quantity()),
                () -> assertEquals(25000, stored.item().dailyRate()));
        assertEquals(1, rowCount("rental_items"));
    }

    @Test
    @DisplayName("Оновлення обладнання змінює запис у БД")
    void updateEquipment_changesStoredRecord() throws Exception {
        var saved = saveEquipment("TENT-01", 30000, 2);

        equipment.update(new Equipment(saved.id(), "TENT-01-XL", 45000, 7));

        assertEquals(new Equipment(saved.id(), "TENT-01-XL", 45000, 7), equipment.findById(saved.id()));
    }

    @Test
    @DisplayName("markReturned змінює статус і доплату лише активної оренди")
    void markReturned_changesStatusInDatabase() throws Exception {
        var item = saveEquipment("BIKE-01", 15000, 3);
        var saved = rentals.create(activeOrder("RENT-REPO-2", item, 1, 2, false));

        rentals.markReturned(saved.id(), 0);

        var stored = rentals.findById(saved.id());
        assertEquals(RentalStatus.Returned, stored.status());
        assertEquals(0, stored.lateFee());
        assertEquals(30000, stored.total());
        assertThrows(RentalRejectedException.class, () -> rentals.markReturned(saved.id(), 0));
        assertEquals(RentalStatus.Returned, rentals.findById(saved.id()).status());
    }

    @Test
    @DisplayName("findRentals фільтрує за обладнанням і статусом та впорядковує за id")
    void findRentals_filtersByEquipmentAndStatus() throws Exception {
        var first = saveEquipment("KAYAK-01", 20000, 10);
        var second = saveEquipment("KAYAK-02", 20000, 10);
        var a = rentals.create(activeOrder("RENT-A", first, 1, 1, false));
        var b = rentals.create(activeOrder("RENT-B", first, 1, 1, false));
        var returned = rentals.create(activeOrder("RENT-C", first, 1, 1, false));
        rentals.create(activeOrder("RENT-D", second, 1, 1, false));
        rentals.markReturned(returned.id(), 0);

        var active = rentals.findRentals(first.id(), RentalStatus.Active);

        assertEquals(List.of(a.id(), b.id()), active.stream().map(RentalOrder::id).toList());
        assertEquals(List.of(returned.id()),
                rentals.findRentals(first.id(), RentalStatus.Returned).stream().map(RentalOrder::id).toList());
    }

    @Test
    @DisplayName("Видалення оренди каскадно видаляє позицію, а обладнання з позиціями видалити не можна")
    void deleteRental_cascadesItem_andProtectsEquipment() throws Exception {
        var item = saveEquipment("SKI-01", 12000, 6);
        var saved = rentals.create(activeOrder("RENT-REPO-3", item, 1, 1, false));

        assertThrows(DataIntegrityViolationException.class, () -> equipment.delete(item.id()));
        assertNotNull(equipment.findById(item.id()));

        rentals.delete(saved.id());

        assertNull(rentals.findById(saved.id()));
        assertEquals(0, rowCount("rental_items"));
        equipment.delete(item.id());
        assertNull(equipment.findById(item.id()));
    }
}
