package com.picsou.adapter;

import java.net.URI;

/** One HTTP exchange. Production uses {@code java.net.http.HttpClient}; tests pass a fake. */
interface SimplefinTransport {

    Response send(String method, URI uri, String authorization, int maxBody);

    record Response(int status, String body) {}
}
