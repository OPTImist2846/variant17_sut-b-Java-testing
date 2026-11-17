package ua.course.rental;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DuplicateKeyException;

@TestMethodOrder(MethodOrderer.Random.class)
@DisplayName("Service → Repository → Database")
class RentalServiceIntegrationTest extends IntegrationTestSupport {
    static final long STANDARD_RATE = 10000;
    static final long PREMIUM_THRESHOLD_RATE = 50000;

    RentalPolicy policy;
    RentalService service;

    @BeforeEach
    void createService() {
        policy = mock(RentalPolicy.class);
        when(policy.isAllowed(any(), anyInt(), anyInt())).thenReturn(true);
        service = new RentalService(equipment, rentals, policy);
    }

    @Test
    @DisplayName("Успішне оформлення створює активну оренду з правильною сумою")
    void rentStandardEquipment_createsActiveRental_serviceState() throws Exception {
        var item = saveEquipment("CAM-01", STANDARD_RATE, 5);

        var result = service.rent(new RentalRequest("RENT-001", item.id(), 2, 3, false));

        var stored = rentals.findById(result.id());
        assertNotNull(stored);
        assertEquals(result, stored);
        assertAll(
                () -> assertEquals(60000, stored.total()),
                () -> assertEquals(0, stored.lateFee()),
                () -> assertEquals(RentalStatus.Active, stored.status()),
                () -> assertEquals(item.id(), stored.item().equipmentId()),
                () -> assertEquals(2, stored.item().quantity()),
                () -> assertEquals(STANDARD_RATE, stored.item().dailyRate()));
        assertEquals(5, equipment.findById(item.id()).stock());
        verify(policy).isAllowed(item, 2, 3);
    }

    @Test
    @DisplayName("Нестача обладнання відхиляє оренду без запису в БД і без зовнішньої перевірки")
    void rentMoreThanStock_isRejectedWithoutRecord_serviceState() throws Exception {
        var item = saveEquipment("CAM-02", STANDARD_RATE, 3);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-002", item.id(), 4, 2, false)));

        assertTrue(rentals.findRentals(item.id(), RentalStatus.Active).isEmpty());
        assertEquals(0, rowCount("rentals"));
        assertEquals(0, rowCount("rental_items"));
        verifyNoInteractions(policy);
    }

    @Test
    @DisplayName("Персональне правило: преміальний клієнт орендує 5 одиниць по 500 грн, сума зберігається в БД")
    void premiumRentAtThresholdWithFiveUnits_isStored_persistedState() throws Exception {
        var item = saveEquipment("PRO-CAM-01", PREMIUM_THRESHOLD_RATE, 10);

        var result = service.rent(new RentalRequest("RENT-PREMIUM-5", item.id(), 5, 2, true));

        var stored = rentals.findById(result.id());
        assertNotNull(stored);
        assertAll(
                () -> assertEquals(500000, stored.total()),
                () -> assertTrue(stored.premium()),
                () -> assertEquals(RentalStatus.Active, stored.status()),
                () -> assertEquals(5, stored.item().quantity()),
                () -> assertEquals(PREMIUM_THRESHOLD_RATE, stored.item().dailyRate()));
        assertEquals(1, rentals.findRentals(item.id(), RentalStatus.Active).size());
        verify(policy).isAllowed(item, 5, 2);
    }

    @Test
    @DisplayName("Персональне правило: звичайний клієнт не може орендувати обладнання від 500 грн")
    void nonPremiumRentAtThreshold_isRejectedBeforePolicy() throws Exception {
        var item = saveEquipment("PRO-CAM-02", PREMIUM_THRESHOLD_RATE, 10);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-REGULAR", item.id(), 1, 1, false)));

        assertTrue(rentals.findRentals(item.id(), RentalStatus.Active).isEmpty());
        assertEquals(0, rowCount("rentals"));
        verify(policy, never()).isAllowed(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Персональне правило: преміальний клієнт не може взяти 6 одиниць дорогого обладнання")
    void premiumRentOfSixExpensiveUnits_isRejectedWithoutRecord() throws Exception {
        var item = saveEquipment("PRO-CAM-03", 80000, 10);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-PREMIUM-6", item.id(), 6, 1, true)));

        assertEquals(0, rowCount("rentals"));
        assertEquals(0, rowCount("rental_items"));
        verifyNoInteractions(policy);
    }

    @Test
    @DisplayName("Межа правила: 499,99 грн доступні звичайному клієнту з загальним лімітом 20 одиниць")
    void nonPremiumRentJustBelowThreshold_withMaxQuantity_isStored() throws Exception {
        var item = saveEquipment("CAM-03", PREMIUM_THRESHOLD_RATE - 1, 20);

        var result = service.rent(new RentalRequest("RENT-BELOW", item.id(), 20, 1, false));

        var stored = rentals.findById(result.id());
        assertEquals((PREMIUM_THRESHOLD_RATE - 1) * 20, stored.total());
        assertEquals(20, stored.item().quantity());
        assertEquals(RentalStatus.Active, stored.status());
    }

    @Test
    @DisplayName("Відмова зовнішньої політики (Mock) не створює оренду")
    void externalPolicyDenial_leavesDatabaseUnchanged() throws Exception {
        var item = saveEquipment("CAM-04", STANDARD_RATE, 5);
        when(policy.isAllowed(any(), anyInt(), anyInt())).thenReturn(false);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-DENIED", item.id(), 1, 1, false)));

        assertEquals(0, rowCount("rentals"));
        verify(policy).isAllowed(item, 1, 1);
    }

    @Test
    @DisplayName("Повернення змінює статус у БД, повторне повернення не змінює стан")
    void returnRental_marksReturned_andRepeatedReturnIsRejected() throws Exception {
        var item = saveEquipment("CAM-05", STANDARD_RATE, 5);
        var rental = service.rent(new RentalRequest("RENT-RETURN", item.id(), 1, 4, false));

        var returned = service.returnRental(rental.id());

        var stored = rentals.findById(rental.id());
        assertEquals(returned, stored);
        assertEquals(RentalStatus.Returned, stored.status());
        assertEquals(40000, stored.total());
        assertEquals(0, stored.lateFee());
        assertThrows(RentalRejectedException.class, () -> service.returnRental(rental.id()));
        assertEquals(stored, rentals.findById(rental.id()));
    }

    @Test
    @DisplayName("Зміна ціни обладнання не змінює збережену оренду")
    void equipmentPriceChange_doesNotAffectStoredRental() throws Exception {
        var item = saveEquipment("CAM-06", STANDARD_RATE, 5);
        var rental = service.rent(new RentalRequest("RENT-SNAPSHOT", item.id(), 1, 2, false));

        equipment.update(new Equipment(item.id(), item.code(), 90000, item.stock()));

        var stored = rentals.findById(rental.id());
        assertEquals(20000, stored.total());
        assertEquals(STANDARD_RATE, stored.item().dailyRate());
    }

    @Test
    @DisplayName("Повторний код оренди відхиляється БД, другий запис не створюється")
    void duplicateReference_isRejectedByDatabase() throws Exception {
        var item = saveEquipment("CAM-07", STANDARD_RATE, 5);
        service.rent(new RentalRequest("RENT-DUP", item.id(), 1, 1, false));

        assertThrows(DuplicateKeyException.class,
                () -> service.rent(new RentalRequest("RENT-DUP", item.id(), 1, 1, false)));

        assertEquals(1, rowCount("rentals"));
        assertEquals(1, rowCount("rental_items"));
    }

    @Test
    @DisplayName("Навмисно невдалий тест (контрольна вимога лабораторної)")
    void intentionallyFailing_controlRequirement() throws Exception {
        var item = saveEquipment("CAM-08", STANDARD_RATE, 5);

        var result = service.rent(new RentalRequest("RENT-FAIL", item.id(), 1, 1, false));

        assertEquals(STANDARD_RATE + 1, rentals.findById(result.id()).total());
    }
}
