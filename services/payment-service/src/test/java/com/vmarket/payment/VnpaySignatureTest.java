package com.vmarket.payment;

import static org.assertj.core.api.Assertions.*;

import com.vmarket.payment.client.VnpaySignature;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

class VnpaySignatureTest {
    private static final String SECRET = "test-hash-secret";
    private final VnpaySignature signature = new VnpaySignature();

    private Map<String, String> params() {
        var params = new TreeMap<String, String>();
        params.put("vnp_TxnRef", "1000001T1790000000");
        params.put("vnp_Amount", "300000");
        params.put("vnp_OrderInfo", "Thanh toan don hang 1000001");
        params.put("vnp_ResponseCode", "00");
        return params;
    }

    @Test
    void signedParametersVerifyAndHexIsCaseInsensitive() {
        var params = params();
        String hash = signature.sign(params, SECRET);
        assertThat(hash).hasSize(128).matches("[0-9a-f]+");
        assertThat(signature.verify(params, hash, SECRET)).isTrue();
        assertThat(signature.verify(params, hash.toUpperCase(), SECRET)).isTrue();
    }

    @Test
    void anyTamperedValueOrWrongSecretIsRejected() {
        var params = params();
        String hash = signature.sign(params, SECRET);
        params.put("vnp_Amount", "100");
        assertThat(signature.verify(params, hash, SECRET)).isFalse();
        assertThat(signature.verify(params(), hash, "other-secret")).isFalse();
        assertThat(signature.verify(params(), "abc", SECRET)).isFalse();
        assertThat(signature.verify(params(), null, SECRET)).isFalse();
        assertThat(signature.verify(params(), hash, "")).isFalse();
    }

    @Test
    void spaceIsEncodedAsPlusAndParametersAreSortedWithoutEmptyValues() {
        var params = params();
        params.put("vnp_BankCode", "");
        assertThat(signature.hashData(params))
                .isEqualTo(
                        "vnp_Amount=300000"
                                + "&vnp_OrderInfo=Thanh+toan+don+hang+1000001"
                                + "&vnp_ResponseCode=00"
                                + "&vnp_TxnRef=1000001T1790000000");
    }

    @Test
    void secureHashFieldsAreIgnoredWhenSigningAndVerifying() {
        var params = params();
        String hash = signature.sign(params, SECRET);
        params.put("vnp_SecureHash", hash);
        params.put("vnp_SecureHashType", "HmacSHA512");
        assertThat(signature.sign(params, SECRET)).isEqualTo(hash);
        assertThat(signature.verify(params, hash, SECRET)).isTrue();
    }
}
