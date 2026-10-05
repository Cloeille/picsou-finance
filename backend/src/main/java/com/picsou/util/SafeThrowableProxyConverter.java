package com.picsou.util;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * {@code %wEx} for {@code logback-spring.xml}: Spring Boot's stack trace layout, with each
 * exception message (causes and suppressed included) neutralised. The trace stays multi-line;
 * only the text an upstream service or caller can put in a message is flattened.
 */
public class SafeThrowableProxyConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return super.throwableProxyToString(new NeutralizedThrowableProxy(tp));
    }

    private record NeutralizedThrowableProxy(IThrowableProxy delegate) implements IThrowableProxy {

        @Override
        public String getMessage() {
            String message = delegate.getMessage();
            return message == null ? null : LogSanitizer.neutralize(message);
        }

        @Override
        public String getClassName() {
            return delegate.getClassName();
        }

        @Override
        public StackTraceElementProxy[] getStackTraceElementProxyArray() {
            return delegate.getStackTraceElementProxyArray();
        }

        @Override
        public int getCommonFrames() {
            return delegate.getCommonFrames();
        }

        @Override
        public IThrowableProxy getCause() {
            IThrowableProxy cause = delegate.getCause();
            return cause == null ? null : new NeutralizedThrowableProxy(cause);
        }

        @Override
        public IThrowableProxy[] getSuppressed() {
            IThrowableProxy[] suppressed = delegate.getSuppressed();
            if (suppressed == null) return null;
            IThrowableProxy[] wrapped = new IThrowableProxy[suppressed.length];
            for (int i = 0; i < suppressed.length; i++) {
                wrapped[i] = new NeutralizedThrowableProxy(suppressed[i]);
            }
            return wrapped;
        }

        @Override
        public boolean isCyclic() {
            return delegate.isCyclic();
        }
    }
}
