package com.vmarket.payment.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "payment_order_states") @Getter @Setter @NoArgsConstructor
public class PaymentOrderState {
    @Id @Column(length = 26) private String orderId;
    @Column(nullable = false) private boolean cancelled;
}
