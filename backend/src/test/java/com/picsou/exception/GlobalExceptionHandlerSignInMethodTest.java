package com.picsou.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerSignInMethodTest {

    @Test
    void signInMethodRefusalKeepsItsStatusDetailAndCode() {
        var detail = new GlobalExceptionHandler().handleSignInMethod(
            new SignInMethodException(HttpStatus.CONFLICT, "LAST_SIGN_IN_METHOD", "Keep one way to sign in"));

        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getDetail()).isEqualTo("Keep one way to sign in");
        assertThat(detail.getProperties()).containsEntry("code", "LAST_SIGN_IN_METHOD");
    }
}
