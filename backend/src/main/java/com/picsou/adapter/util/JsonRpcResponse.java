package com.picsou.adapter.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.exception.WalletRpcException;

/**
 * Validation for JSON-RPC 2.0 responses shared by the on-chain wallet adapters.
 *
 * <p>A response is only usable when it carries a {@code result} and no
 * {@code error}. The adapters previously read {@code response.path("result")},
 * which returns a non-null {@code MissingNode} when {@code result} is absent —
 * so a JSON-RPC error payload silently defaulted to a 0 balance. This helper
 * uses {@link JsonNode#get(String)} (null when absent) to tell a <em>missing</em>
 * result apart from a <em>legitimate zero</em> one, and throws instead of masking
 * the failure.
 */
public final class JsonRpcResponse {

    private JsonRpcResponse() {
    }

    /**
     * Validates a JSON-RPC 2.0 envelope and returns its {@code result} node.
     *
     * @param response the parsed body, or {@code null} if the call returned nothing
     * @param context  short diagnostic label, e.g. {@code "Ethereum eth_getBalance"}
     * @return the present {@code result} node (a {@code TextNode} for ETH, an
     *         {@code ObjectNode} for SOL); callers may safely navigate it with
     *         {@code path(...)}
     * @throws WalletRpcException if {@code response} is null, carries a non-null
     *         {@code error}, or has a missing/null {@code result}
     */
    public static JsonNode requireResult(JsonNode response, String context) {
        if (response == null) {
            throw new WalletRpcException(context + ": RPC returned no response");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new WalletRpcException(context + ": RPC error "
                + error.path("code").asText("?") + " - "
                + error.path("message").asText("unknown"));
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            throw new WalletRpcException(context + ": RPC response missing 'result'");
        }
        return result;
    }
}
