package com.picsou.service.sso;

/** A refusal inside the SSO flow; the SSO handlers turn it into an {@code ssoError} redirect. */
public class SsoFlowException extends RuntimeException {

    private final String code;

    public SsoFlowException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
