package com.picsou.util;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * {@code %m}/{@code %msg}/{@code %message} for {@code logback-spring.xml}: the formatted message
 * with line breaks and control characters neutralised, so a logged value cannot forge a new log
 * line or inject terminal escape sequences.
 */
public class SafeMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        return message == null || isClean(message) ? message : LogSanitizer.neutralize(message);
    }

    /** Most messages are clean; skip the regex pass for them. */
    static boolean isClean(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.getType(c) == Character.CONTROL || c == ' ' || c == ' ') {
                return false;
            }
        }
        return true;
    }
}
