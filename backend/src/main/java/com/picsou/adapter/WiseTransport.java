package com.picsou.adapter;

import java.net.URI;
import java.time.Duration;

/** One HTTP GET. Production uses {@code java.net.http.HttpClient}; tests pass a fake. */
interface WiseTransport {

    Response get(URI uri, String authorization, int maxBody, Duration timeout);

    record Response(int status, String body) {}
}
