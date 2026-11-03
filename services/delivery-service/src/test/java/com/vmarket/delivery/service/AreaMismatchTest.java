package com.vmarket.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AreaMismatchTest {

    @Test
    void cungTinh_caHaiDeuKhongCoQuanHuyen_khongLechKhuVuc() {
        assertThat(DeliveryService.isAreaMismatch("da nang", null, "da nang", null)).isFalse();
        assertThat(DeliveryService.isAreaMismatch("da nang", "", "da nang", "")).isFalse();
        assertThat(DeliveryService.isAreaMismatch("da nang", "   ", "da nang", null)).isFalse();
    }

    @Test
    void cungTinh_shipperToanTinh_donCoQuanHuyen_khongLechKhuVuc() {
        assertThat(DeliveryService.isAreaMismatch("da nang", null, "da nang", "hai chau")).isFalse();
        assertThat(DeliveryService.isAreaMismatch("da nang", "", "da nang", "hai chau")).isFalse();
    }

    @Test
    void cungTinh_shipperCoQuanHuyen_donKhongCoQuanHuyen_khongLechKhuVuc() {
        assertThat(DeliveryService.isAreaMismatch("da nang", "hai chau", "da nang", null)).isFalse();
        assertThat(DeliveryService.isAreaMismatch("da nang", "hai chau", "da nang", "")).isFalse();
    }

    @Test
    void cungTinh_caHaiCungQuanHuyen_khongLechKhuVuc() {
        assertThat(DeliveryService.isAreaMismatch("da nang", "hai chau", "da nang", "hai chau")).isFalse();
    }

    @Test
    void cungTinh_khacQuanHuyen_lechKhuVuc() {
        assertThat(DeliveryService.isAreaMismatch("da nang", "son tra", "da nang", "hai chau")).isTrue();
    }

    @Test
    void khacTinh_duKhongCoQuanHuyen_vanLechKhuVuc() {
        assertThat(DeliveryService.isAreaMismatch("ha noi", null, "da nang", null)).isTrue();
        assertThat(DeliveryService.isAreaMismatch("ha noi", "ba dinh", "da nang", "hai chau")).isTrue();
        assertThat(DeliveryService.isAreaMismatch("ha noi", "ba dinh", "da nang", null)).isTrue();
    }
}

