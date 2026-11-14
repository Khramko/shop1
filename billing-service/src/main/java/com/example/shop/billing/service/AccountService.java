package com.example.shop.billing.service;

import com.example.shop.billing.dto.AccountResponse;
import com.example.shop.billing.model.Account;
import com.example.shop.billing.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public AccountResponse getByUserId(Long userId) {
        Account acc = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Account not found for userId=" + userId));
        return new AccountResponse(acc.getId(), acc.getUserId(), acc.getBalance());
    }

    @Transactional
    public AccountResponse deposit(Long userId, BigDecimal amount) {
        Account acc = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Account not found for userId=" + userId));
        acc.setBalance(acc.getBalance().add(amount));
        accountRepository.save(acc);
        log.info("Deposited {} to userId={} newBalance={}", amount, userId, acc.getBalance());
        return new AccountResponse(acc.getId(), acc.getUserId(), acc.getBalance());
    }
}
