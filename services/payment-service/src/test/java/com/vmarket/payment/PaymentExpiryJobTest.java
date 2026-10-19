package com.vmarket.payment;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vmarket.payment.client.PayosClient;
import com.vmarket.payment.entity.PaymentTransaction;
import com.vmarket.payment.repository.PaymentRepository;
import com.vmarket.payment.service.PaymentExpiryJob;
import com.vmarket.payment.service.PaymentStore;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

class PaymentExpiryJobTest {
    @Test
    void oneBrokenPaymentDoesNotStopExpiryOfTheNextOne() {
        var repository = mock(PaymentRepository.class);
        var store = mock(PaymentStore.class);
        var first = new PaymentTransaction();
        first.setId(1L);
        var second = new PaymentTransaction();
        second.setId(2L);
        when(repository
                        .findByStatusInAndSettlementExpiresAtLessThanEqualOrderBySettlementExpiresAtAscIdAsc(
                                any(), any(), any()))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("bad row")).when(store).expire(1L);
        new PaymentExpiryJob(repository, store, mock(PayosClient.class), Clock.systemUTC()).run();
        verify(store).expire(1L);
        verify(store).expire(2L);
    }
}
