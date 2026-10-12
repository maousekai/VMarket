package com.vmarket.payment;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.vmarket.payment.client.PayosSignature;
import java.util.*;
class PayosSignatureTest {
    @Test void matchesPayosPublishedPaymentWebhookVector() {
        // Public test vector from payos.vn/docs/tich-hop-webhook/kiem-tra-du-lieu-voi-signature/.
        var values = new TreeMap<String,Object>();
        values.put("orderCode",123); values.put("amount",3000); values.put("description","VQRIO123");
        values.put("accountNumber","12345678"); values.put("reference","TF230204212323");
        values.put("transactionDateTime","2023-02-04 18:25:00"); values.put("currency","VND");
        values.put("paymentLinkId","124c33293c43417ab7879e14c8d9eb18"); values.put("code","00"); values.put("desc","Thành công");
        for (String key : List.of("counterAccountBankId","counterAccountBankName","counterAccountName","counterAccountNumber","virtualAccountName","virtualAccountNumber")) values.put(key, "");
        var signature = new PayosSignature(JsonMapper.builder().build());
        assertThat(signature.sign(values, "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675"))
            .isEqualTo("412e915d2871504ed31be63c8f62a149a4410d34c4c42affc9006ef9917eaa03");
    }
}
