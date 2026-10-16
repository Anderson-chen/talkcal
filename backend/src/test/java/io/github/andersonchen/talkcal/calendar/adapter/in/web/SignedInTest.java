package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.security.Principal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedIn")
class SignedInTest {

    @Test
    @DisplayName("登入者的名字就是擁有者 ID")
    void nameIsOwnerId() {
        Principal amy = () -> "3f1c8a2e-0000-4000-8000-000000000001";

        assertEquals(OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001"), SignedIn.owner(amy));
    }

    @Test
    @DisplayName("沒有登入者、名字不是帳號 ID（登入那一頭壞了）：IllegalStateException（502），不是 IllegalArgumentException（400）")
    void brokenSignInIsUpstreamFailure() {
        Principal alice = () -> "alice";

        assertThrows(IllegalStateException.class, () -> SignedIn.owner(null));
        assertThrows(IllegalStateException.class, () -> SignedIn.owner(alice));
    }
}
