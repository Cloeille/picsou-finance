package com.picsou.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** A UTF-8 response body read up to a byte cap, so a remote server cannot exhaust memory. */
final class BoundedUtf8Body {

    private BoundedUtf8Body() {}

    /** Stops reading once {@code maxBody} bytes have arrived, including a lying Content-Length. */
    static HttpResponse.BodyHandler<String> handler(int maxBody) {
        return info -> {
            long advertised = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            return subscriber(maxBody, advertised > maxBody);
        };
    }

    static boolean isTooLarge(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof ResponseTooLarge) return true;
        }
        return false;
    }

    private static HttpResponse.BodySubscriber<String> subscriber(int maxBody, boolean alreadyTooBig) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<String> body = new CompletableFuture<>();
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            private int seen;

            @Override
            public CompletionStage<String> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                if (alreadyTooBig) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) {
                    int n = buffer.remaining();
                    if (seen > maxBody - n) {
                        subscription.cancel();
                        body.completeExceptionally(new ResponseTooLarge());
                        return;
                    }
                    byte[] chunk = new byte[n];
                    buffer.get(chunk);
                    out.write(chunk, 0, n);
                    seen += n;
                }
            }

            @Override
            public void onError(Throwable throwable) {
                body.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                if (!body.isDone()) {
                    body.complete(out.toString(StandardCharsets.UTF_8));
                }
            }
        };
    }

    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge() {
            super("response too large");
        }
    }
}
