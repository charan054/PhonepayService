package com.example.phonepayservice.service;

import com.example.phonepayservice.dto.PayeeResponse;
import com.example.phonepayservice.entity.SavedPayee;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.PayeeNotFoundException;
import com.example.phonepayservice.repository.SavedPayeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayeeServiceTest {

    private static final long OWNER = 9876543210L;
    private static final long PAYEE = 9123456789L;
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private static class FixedClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW; }
    }

    @Mock
    private SavedPayeeRepository payees;

    private PayeeService service;

    @BeforeEach
    void setUp() {
        service = new PayeeService(payees, new FixedClock());
    }

    private SavedPayee stored(long id, long ownerPhno, long payeePhno, String nickname, Instant createdAt) {
        SavedPayee p = new SavedPayee();
        p.setId(id);
        p.setOwnerPhno(ownerPhno);
        p.setPayeePhno(payeePhno);
        p.setNickname(nickname);
        p.setCreatedAt(createdAt);
        return p;
    }

    // ---------- save ----------

    @Test
    void save_newPayee_createsIt_withTheGivenNickname_andTheCurrentMoment() {
        when(payees.findByOwnerPhnoAndPayeePhno(OWNER, PAYEE)).thenReturn(Optional.empty());
        when(payees.save(any(SavedPayee.class))).thenAnswer(inv -> inv.getArgument(0));

        PayeeResponse response = service.save(OWNER, PAYEE, "Ravi");

        assertEquals(PAYEE, response.payeePhno());
        assertEquals("Ravi", response.nickname());

        ArgumentCaptor<SavedPayee> captor = ArgumentCaptor.forClass(SavedPayee.class);
        verify(payees).save(captor.capture());
        assertEquals(OWNER, captor.getValue().getOwnerPhno());
        assertEquals(NOW, captor.getValue().getCreatedAt());
    }

    @Test
    void save_sameNumberAgain_updatesTheNickname_insteadOfCreatingASecondEntry() {
        Instant originalCreatedAt = Instant.parse("2020-01-01T00:00:00Z");
        SavedPayee existing = stored(1, OWNER, PAYEE, "Old name", originalCreatedAt);
        when(payees.findByOwnerPhnoAndPayeePhno(OWNER, PAYEE)).thenReturn(Optional.of(existing));
        when(payees.save(any(SavedPayee.class))).thenAnswer(inv -> inv.getArgument(0));

        PayeeResponse response = service.save(OWNER, PAYEE, "New name");

        assertEquals("New name", response.nickname());
        ArgumentCaptor<SavedPayee> captor = ArgumentCaptor.forClass(SavedPayee.class);
        verify(payees).save(captor.capture());
        assertEquals(1, captor.getValue().getId());   // the same row, not a new one
        assertEquals(originalCreatedAt, captor.getValue().getCreatedAt());   // when it was FIRST saved, unchanged
    }

    @Test
    void save_blankNickname_isStoredAsNull() {
        when(payees.findByOwnerPhnoAndPayeePhno(OWNER, PAYEE)).thenReturn(Optional.empty());
        when(payees.save(any(SavedPayee.class))).thenAnswer(inv -> inv.getArgument(0));

        PayeeResponse response = service.save(OWNER, PAYEE, "   ");

        assertNull(response.nickname());
    }

    @Test
    void save_yourself_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> service.save(OWNER, OWNER, "Me"));

        verify(payees, never()).save(any());
    }

    // ---------- list ----------

    @Test
    void listPayees_returnsThemAsResponses() {
        when(payees.findByOwnerPhnoOrderByIdDesc(OWNER)).thenReturn(List.of(
                stored(2, OWNER, PAYEE, "Ravi", NOW),
                stored(1, OWNER, 9000000001L, "Meena", NOW)));

        List<PayeeResponse> list = service.listPayees(OWNER);

        assertEquals(2, list.size());
        assertEquals("Ravi", list.get(0).nickname());
        assertEquals("Meena", list.get(1).nickname());
    }

    @Test
    void listPayees_noneSaved_isEmpty() {
        when(payees.findByOwnerPhnoOrderByIdDesc(OWNER)).thenReturn(List.of());

        assertEquals(List.of(), service.listPayees(OWNER));
    }

    // ---------- delete ----------

    @Test
    void delete_removesIt() {
        when(payees.deleteByOwnerPhnoAndPayeePhno(OWNER, PAYEE)).thenReturn(1L);

        service.delete(OWNER, PAYEE);

        verify(payees).deleteByOwnerPhnoAndPayeePhno(OWNER, PAYEE);
    }

    @Test
    void delete_notSaved_throwsPayeeNotFound() {
        when(payees.deleteByOwnerPhnoAndPayeePhno(OWNER, PAYEE)).thenReturn(0L);

        assertThrows(PayeeNotFoundException.class, () -> service.delete(OWNER, PAYEE));
    }
}
