package com.vmarket.payment.service;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
public class PaymentOrderStateInitializer {
    private final JdbcTemplate jdbc;

    // Uses the caller's connection; a racing initializer cannot abort its transaction.
    @Transactional
    public void ensure(String orderId) {
        jdbc.update(
                "insert into payment_order_states (order_id, cancelled) values (?, false) on"
                        + " conflict do nothing",
                orderId);
    }
}
