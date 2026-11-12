package com.picsou.exception;

/** AI categorization was requested while no AI provider is configured for the instance. */
public class AiProviderNotConfiguredException extends RuntimeException {

    public static final String CODE = "AI_PROVIDER_NOT_CONFIGURED";

    public AiProviderNotConfiguredException() {
        super("No AI provider is configured. An administrator can set one in Administration > AI categorization.");
    }
}
