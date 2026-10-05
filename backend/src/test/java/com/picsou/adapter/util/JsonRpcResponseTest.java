package com.picsou.adapter.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.WalletRpcException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRpcResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void requireResult_returnsResultNode_whenPresent() {
        JsonNode result = JsonRpcResponse.requireResult(
            parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xDE0B6B3A7640000\"}"), "ctx");

        assertThat(result.asText()).isEqualTo("0xDE0B6B3A7640000");
    }

    @Test
    void requireResult_allowsLegitimateHexZero() {
        JsonNode result = JsonRpcResponse.requireResult(
            parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x0\"}"), "ctx");

        assertThat(result.asText()).isEqualTo("0x0");
    }

    @Test
    void requireResult_allowsLegitimateNumericZero() {
        JsonNode result = JsonRpcResponse.requireResult(
            parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":0}}"), "ctx");

        assertThat(result.path("value").asLong(-1)).isZero();
    }

    @Test
    void requireResult_allowsEmptyArrayResult() {
        JsonNode result = JsonRpcResponse.requireResult(
            parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"value\":[]}}"), "ctx");

        assertThat(result.path("value").isArray()).isTrue();
        assertThat(result.path("value")).isEmpty();
    }

    @Test
    void requireResult_throws_whenResponseIsNull() {
        assertThatThrownBy(() -> JsonRpcResponse.requireResult(null, "ctx"))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("no response");
    }

    @Test
    void requireResult_throws_onRpcError() {
        assertThatThrownBy(() -> JsonRpcResponse.requireResult(
            parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"rate limited\"}}"), "ctx"))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("-32000")
            .hasMessageContaining("rate limited");
    }

    @Test
    void requireResult_throws_whenResultMissing() {
        assertThatThrownBy(() -> JsonRpcResponse.requireResult(
            parse("{\"jsonrpc\":\"2.0\",\"id\":1}"), "ctx"))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("missing 'result'");
    }

    @Test
    void requireResult_throws_whenResultIsExplicitNull() {
        assertThatThrownBy(() -> JsonRpcResponse.requireResult(
            parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}"), "ctx"))
            .isInstanceOf(WalletRpcException.class)
            .hasMessageContaining("missing 'result'");
    }
}
