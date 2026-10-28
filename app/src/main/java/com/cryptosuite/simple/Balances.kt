package com.cryptosuite.simple

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Aggregates real API figures only. null = data unavailable (never invented). */
object Balances {
    val ORDER = listOf("MATIC", "BNB", "ETH", "USDT", "USDC", "BTC", "LTC", "BCH")

    fun norm(sym: String): String? {
        val s = sym.uppercase()
        return when {
            s.contains("USDT") -> "USDT"; s.contains("USDC") -> "USDC"
            s.contains("MATIC") || s.contains("POL") -> "MATIC"
            s.contains("BNB") -> "BNB"; s.contains("ETH") -> "ETH"; else -> null
        }
    }

    fun aggregate(summary: JSONObject?, portfolio: JSONObject?): LinkedHashMap<String, BigDecimal?> {
        val out = LinkedHashMap<String, BigDecimal?>(); ORDER.forEach { out[it] = BigDecimal.ZERO }
        val unavailable = HashSet<String>()
        if (summary == null) unavailable += listOf("BTC", "LTC", "BCH")
        summary?.optJSONObject("per_chain")?.let { pc ->
            for ((k, sym) in listOf("btc" to "BTC", "ltc" to "LTC", "bch" to "BCH")) {
                val r = pc.optJSONObject(k) ?: continue
                if (!r.optBoolean("balance_available", false) && r.optInt("count") > 0) unavailable += sym
                else out[sym] = BigDecimal.valueOf(r.optLong("balance_sat", 0)).movePointLeft(8)
            }
        }
        val seen = HashSet<String>()
        fun addNative(addr: String, chainId: Int, symbol: String, wei: String?) {
            val key = "${addr.lowercase()}:$chainId"; if (!seen.add(key)) return
            val s = norm(symbol) ?: return
            if (wei == null) { unavailable += s; return }
            out[s] = (out[s] ?: BigDecimal.ZERO) + BigDecimal(wei).movePointLeft(18)
        }
        val tokSeen = HashSet<String>()
        portfolio?.optJSONArray("accounts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i); val addr = a.optJSONObject("account")?.optString("address") ?: ""
                val bl = a.optJSONArray("balances") ?: continue
                for (j in 0 until bl.length()) {
                    val b = bl.getJSONObject(j); val cid = b.optInt("chain_id")
                    addNative(addr, cid, b.optString("native_symbol"), if (b.isNull("native_wei")) null else b.optString("native_wei"))
                    val toks = b.optJSONArray("tokens") ?: continue
                    for (t in 0 until toks.length()) {
                        val tk = toks.getJSONObject(t); val s = norm(tk.optString("symbol")) ?: continue
                        if (s != "USDT" && s != "USDC") continue
                        if (!tokSeen.add("${addr.lowercase()}:$cid:${tk.optString("address").lowercase()}")) continue
                        if (tk.isNull("balance")) { unavailable += s; continue }
                        out[s] = (out[s] ?: BigDecimal.ZERO) + BigDecimal(tk.optString("balance")).movePointLeft(tk.optInt("decimals", 18))
                    }
                }
            }
        }
        summary?.optJSONObject("per_chain")?.optJSONObject("evm")?.optJSONArray("by_account")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i); val ch = a.optJSONArray("chains") ?: continue
                for (j in 0 until ch.length()) {
                    val c = ch.getJSONObject(j)
                    addNative(a.optString("address"), c.optInt("chain_id"), c.optString("native_symbol", c.optString("key")),
                        if (c.has("native_wei")) c.optString("native_wei") else null)
                }
            }
        }
        if (portfolio == null) unavailable += listOf("USDT", "USDC")
        unavailable.forEach { out[it] = null }
        return out
    }

    fun fmt(v: BigDecimal?): String = when {
        v == null -> "нет данных"
        v.signum() == 0 -> "0"
        else -> v.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    }
}
