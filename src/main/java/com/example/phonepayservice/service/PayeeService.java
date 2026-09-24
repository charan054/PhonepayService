package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.PayeeResponse;
import com.example.phonepayservice.entity.SavedPayee;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.PayeeNotFoundException;
import com.example.phonepayservice.repository.SavedPayeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * A person's own saved contacts, so they do not have to retype a phone number every time they pay someone.
 * Validates payeePhno against the bank before saving (the same real-account check the bank's own transfer would
 * eventually make), so a typo is caught immediately rather than only surfacing as a failed payment later.
 */
@Service
public class PayeeService {
    private final SavedPayeeRepository payees;
    private final Clock clock;
    private final BankGateway bank;

    public PayeeService(SavedPayeeRepository payees, Clock clock, BankGateway bank) {
        this.payees = payees;
        this.clock = clock;
        this.bank = bank;
    }

    /**
     * Saving the same phone number again updates its nickname instead of creating a second entry. The bank call
     * happens before the database write and deliberately outside any @Transactional boundary, the same reason
     * PhonepeService itself is never @Transactional around a bank call: no database transaction can span both
     * systems. The find-then-write below doesn't need one either - the database's own unique constraint on
     * (owner_phno, payee_phno) is what actually stops a concurrent duplicate, not transaction isolation.
     */
    public PayeeResponse save(long ownerPhno, long payeePhno, String nickname) {
        if (ownerPhno == payeePhno) {
            throw new InvalidRequestException("You cannot save yourself as a payee");
        }
        BankUser payeeAccount = bank.findUser(payeePhno);   // throws UserNotExistException if this isn't a real account

        SavedPayee payee = payees.findByOwnerPhnoAndPayeePhno(ownerPhno, payeePhno).orElseGet(SavedPayee::new);
        payee.setOwnerPhno(ownerPhno);
        payee.setPayeePhno(payeePhno);
        payee.setNickname(nickname == null || nickname.isBlank() ? null : nickname.trim());
        if (payee.getCreatedAt() == null) {
            payee.setCreatedAt(clock.instant());
        }
        return PayeeResponse.from(payees.save(payee), payeeAccount.getName());
    }

    public List<PayeeResponse> listPayees(long ownerPhno) {
        return payees.findByOwnerPhnoOrderByIdDesc(ownerPhno).stream().map(PayeeResponse::from).toList();
    }

    @Transactional
    public void delete(long ownerPhno, long payeePhno) {
        if (payees.deleteByOwnerPhnoAndPayeePhno(ownerPhno, payeePhno) == 0) {
            throw new PayeeNotFoundException("No saved payee with that phone number");
        }
    }
}
