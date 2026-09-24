package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.SavedPayee;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// replace = ANY swaps the MySQL datasource for an in-memory H2 database, so these tests can never touch your real schema.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class SavedPayeeRepositoryTest {

    private static final long ASHA = 9876543210L;
    private static final long RAVI = 9123456789L;
    private static final long MEENA = 9000000001L;

    @Autowired
    private SavedPayeeRepository repository;
    @Autowired
    private EntityManager entityManager;

    private SavedPayee payee(long ownerPhno, long payeePhno, String nickname) {
        SavedPayee p = new SavedPayee();
        p.setOwnerPhno(ownerPhno);
        p.setPayeePhno(payeePhno);
        p.setNickname(nickname);
        p.setCreatedAt(Instant.parse("2026-09-24T10:00:00Z"));
        return p;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findByOwnerPhno_returnsOnlyThatOwnersPayees() {
        repository.save(payee(ASHA, RAVI, "Ravi"));
        repository.save(payee(MEENA, RAVI, "Someone else's Ravi"));
        flushAndClear();

        List<SavedPayee> found = repository.findByOwnerPhnoOrderByIdDesc(ASHA);

        assertEquals(1, found.size());
        assertEquals("Ravi", found.get(0).getNickname());
    }

    @Test
    void findByOwnerPhno_newestSavedFirst() {
        repository.save(payee(ASHA, RAVI, "Ravi"));
        repository.save(payee(ASHA, MEENA, "Meena"));
        flushAndClear();

        List<SavedPayee> found = repository.findByOwnerPhnoOrderByIdDesc(ASHA);

        assertEquals(List.of("Meena", "Ravi"), found.stream().map(SavedPayee::getNickname).toList());
    }

    @Test
    void findByOwnerPhno_ownerWithNoPayees_isEmpty() {
        assertEquals(List.of(), repository.findByOwnerPhnoOrderByIdDesc(ASHA));
    }

    @Test
    void findByOwnerPhnoAndPayeePhno_findsTheMatch() {
        repository.save(payee(ASHA, RAVI, "Ravi"));
        flushAndClear();

        Optional<SavedPayee> found = repository.findByOwnerPhnoAndPayeePhno(ASHA, RAVI);

        assertTrue(found.isPresent());
        assertEquals("Ravi", found.get().getNickname());
        assertTrue(repository.findByOwnerPhnoAndPayeePhno(ASHA, MEENA).isEmpty());
    }

    @Test
    void deleteByOwnerPhnoAndPayeePhno_removesOnlyThatOwnersEntry() {
        repository.save(payee(ASHA, RAVI, "Ravi"));
        repository.save(payee(MEENA, RAVI, "Someone else's Ravi"));
        flushAndClear();

        long removed = repository.deleteByOwnerPhnoAndPayeePhno(ASHA, RAVI);

        assertEquals(1, removed);
        assertEquals(1, repository.count());
        assertTrue(repository.findByOwnerPhnoAndPayeePhno(MEENA, RAVI).isPresent());
    }

    @Test
    void deleteByOwnerPhnoAndPayeePhno_nothingToDelete_returnsZero() {
        assertEquals(0, repository.deleteByOwnerPhnoAndPayeePhno(ASHA, RAVI));
    }

    @Test
    void database_rejectsTheSameOwnerSavingTheSamePayeeTwice() {
        repository.saveAndFlush(payee(ASHA, RAVI, "Ravi"));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(payee(ASHA, RAVI, "A different name for the same number")));
    }

    @Test
    void database_allowsTwoDifferentOwnersToSaveTheSamePayeeNumber() {
        repository.saveAndFlush(payee(ASHA, RAVI, "Ravi"));
        repository.saveAndFlush(payee(MEENA, RAVI, "My Ravi too"));

        assertEquals(2, repository.count());
    }
}
