package com.vmarket.payment.service;
import com.vmarket.payment.entity.PaymentOrderState;
import com.vmarket.payment.repository.PaymentOrderStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
@Component @RequiredArgsConstructor
public class PaymentOrderStateInitializer {
    private final PaymentOrderStateRepository states;
    // Commit the identity before acquiring its domain lock. A concurrent insert can
    // roll back in isolation without poisoning the caller's transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensure(String orderId) {
        if (!states.existsById(orderId)) {
            var state = new PaymentOrderState(); state.setOrderId(orderId); states.saveAndFlush(state);
        }
    }
}
