package com.vmarket.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.vmarket.delivery.domain.DeliveryRules;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** delivery-rules.json o resources phai la ban sao dung cua shared/delivery (mot nguon). */
class DeliveryRulesTest {
    @Test
    void resourceIsAnExactCopyOfTheSharedRules() throws Exception {
        Path shared = Path.of("../../shared/delivery/delivery-rules.json");
        Path copy = Path.of("src/main/resources/delivery-rules.json");
        assertThat(Files.exists(shared)).as("shared/delivery/delivery-rules.json").isTrue();
        assertThat(Files.readString(copy).replace("\r\n", "\n"))
                .as("Chay: node scripts/sync-delivery-rules.mjs")
                .isEqualTo(Files.readString(shared).replace("\r\n", "\n"));
    }

    @Test
    void wardsMapToTheSevenDeliveryZones() {
        DeliveryRules rules = DeliveryRules.get();
        assertThat(rules.zoneOfWard("Phường Hòa Cường")).contains("hai chau");
        assertThat(rules.zoneOfWard("phuong an hai")).contains("son tra");
        assertThat(rules.zoneOfWard("Xã Bà Nà")).contains("hoa vang");
        assertThat(rules.zoneOfWard("Phường Không Có")).isEmpty();
        assertThat(rules.zoneName("hai chau")).isEqualTo("Hải Châu");
    }

    @Test
    void failureReasonsMatchTheAgreedRules() {
        DeliveryRules rules = DeliveryRules.get();
        assertThat(rules.reasons().keySet())
                .containsExactly(
                        "CUSTOMER_UNREACHABLE",
                        "CUSTOMER_RESCHEDULED",
                        "CUSTOMER_REFUSED",
                        "WRONG_ADDRESS",
                        "SHIPPER_ISSUE",
                        "OTHER");
        assertThat(rules.reason("CUSTOMER_REFUSED").orElseThrow().returnImmediately()).isTrue();
        assertThat(rules.reason("OTHER").orElseThrow().noteRequired()).isTrue();
        assertThat(rules.reason("SHIPPER_ISSUE").orElseThrow().shipperFault()).isTrue();
        assertThat(rules.retryHours()).isEqualTo(2);
        assertThat(rules.maxAttempts()).isEqualTo(3);
        assertThat(rules.returnAutoCloseDays()).isEqualTo(3);
    }
}
