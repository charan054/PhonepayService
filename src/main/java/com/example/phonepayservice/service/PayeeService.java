package com.example.phonepayservice.service;

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
 * A person's own saved contacts, so they do not have to retype a phone number every time they pay someone. Never
 * validates that payeePhno is a real bank account - the same lazy-validation approach PhonepeService.sendMoney
 * already takes, which only finds out when a payment is actually attempted.
 */
@Service
public class PayeeService {
    private final SavedPayeeRepository payees;
    private final Clock clock;

    public PayeeService(SavedPayeeRepository payees, Clock clock) {
        this.payees = payees;
        this.clock = clock;
    }

    /** Saving the same phone number again updates its nickname instead of creating a second entry. */
    @Transactional
    public PayeeResponse save(long ownerPhno, long payeePhno, String nickname) {
        if (ownerPhno == payeePhno) {
            throw new InvalidRequestException("You cannot save yourself as a payee");
        }
        SavedPayee payee = payees.findByOwnerPhnoAndPayeePhno(ownerPhno, payeePhno).orElseGet(SavedPayee::new);
        payee.setOwnerPhno(ownerPhno);
        payee.setPayeePhno(payeePhno);
        payee.setNickname(nickname == null || nickname.isBlank() ? null : nickname.trim());
        if (payee.getCreatedAt() == null) {
            payee.setCreatedAt(clock.instant());
        }
        return PayeeResponse.from(payees.save(payee));
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
