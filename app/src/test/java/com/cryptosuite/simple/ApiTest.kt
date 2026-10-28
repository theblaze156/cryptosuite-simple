package com.cryptosuite.simple

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiTest {
    @Test fun headersAnd403() {
        val s = MockWebServer(); s.start()
        s.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        s.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val api = Api(s.url("/").toString(), "edge-test", "bsk_a_b")
        assertEquals("ok", api.health().getString("status"))
        val r = s.takeRequest(); assertEquals("/api/health", r.path)
        assertEquals("edge-test", r.getHeader("X-Crypto-Auth")); assertEquals("bsk_a_b", r.getHeader("X-API-Key"))
        try { api.summary(); fail() } catch (e: ApiError) { assertTrue(e.userMessage.contains("403")) }
        s.shutdown()
    }
    @Test fun unreachable() {
        try { Api("http://127.0.0.1:1", "", "").health(); fail() } catch (e: ApiError) { assertTrue(e.userMessage.isNotEmpty()) }
    }
    @Test fun addPostsJson() {
        val s = MockWebServer(); s.start(); s.enqueue(MockResponse().setBody("""{"imported":1}"""))
        Api(s.url("/").toString(), "x", "bsk_k").add(" 0xabc ", "evm")
        val r = s.takeRequest(); assertEquals("/api/v1/import/watchlist", r.path)
        val b = JSONObject(r.body.readUtf8()); assertEquals("0xabc", b.getString("text")); assertEquals("evm", b.getString("default_chain"))
        s.shutdown()
    }
    @Test fun aggregateRealOnly() {
        val sum = JSONObject("""{"per_chain":{"btc":{"count":1,"balance_sat":150000000,"balance_available":true},
          "ltc":{"count":0,"balance_sat":0,"balance_available":true},"bch":{"count":2,"balance_sat":0,"balance_available":false},
          "evm":{"by_account":[{"address":"0xA","chains":[{"chain_id":1,"native_symbol":"ETH","native_wei":"1000000000000000000"}]}]}},"totals":{"addresses":3}}""")
        val pf = JSONObject("""{"accounts":[{"account":{"address":"0xa"},"balances":[
          {"chain_id":1,"native_symbol":"ETH","native_wei":"1000000000000000000","tokens":[{"symbol":"USDT","address":"0xt","decimals":6,"balance":"2500000"}]},
          {"chain_id":56,"native_symbol":"BNB","native_wei":null,"tokens":[]}]}]}""")
        val a = Balances.aggregate(sum, pf)
        assertEquals("1.5", Balances.fmt(a["BTC"])); assertEquals("0", Balances.fmt(a["LTC"]))
        assertNull(a["BCH"]); assertEquals("1", Balances.fmt(a["ETH"])) // deduped
        assertEquals("2.5", Balances.fmt(a["USDT"])); assertEquals("0", Balances.fmt(a["USDC"])); assertNull(a["BNB"]); assertEquals("0", Balances.fmt(a["MATIC"]))
        val none = Balances.aggregate(null, null); assertNull(none["BTC"]); assertNull(none["USDT"])
    }
}
