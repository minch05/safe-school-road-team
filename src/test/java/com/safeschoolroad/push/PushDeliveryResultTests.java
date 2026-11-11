package com.safeschoolroad.push;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.safeschoolroad.push.PushService.DeliveryResult.*;
class PushDeliveryResultTests {
    @Test void 접수와_일시오류와_만료와_영구거절을_구분한다() {
        for(int code:new int[]{200,201,202,204,299}) assertThat(PushService.classify(code)).isEqualTo(ACCEPTED);
        for(int code:new int[]{408,429,500,503,599}) assertThat(PushService.classify(code)).isEqualTo(RETRY);
        for(int code:new int[]{404,410}) assertThat(PushService.classify(code)).isEqualTo(EXPIRED);
        for(int code:new int[]{301,400,401,403,413}) assertThat(PushService.classify(code)).isEqualTo(REJECTED);
    }
}
