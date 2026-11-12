package com.picsou.adapter;

import java.net.URI;

/** One HTTP GET. Production uses {@code java.net.http.HttpClient}; tests pass a fake. */
interface WiseTransport {

    Response get(URI uri, String authorization, int maxBody);

    record Response(int status, String body) {}
}
