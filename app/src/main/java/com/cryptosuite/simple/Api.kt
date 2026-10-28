package com.cryptosuite.simple

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

class ApiError(val userMessage: String) : Exception(userMessage)

/** Minimal client. Never logs headers or bodies. */
class Api(baseUrl: String, private val edgeKey: String, private val apiKey: String) {
    private val base = baseUrl.trim().trimEnd('/')

    fun request(method: String, path: String, body: String? = null): String {
        val c = try { URL(base + path).openConnection() as HttpURLConnection }
                catch (e: Exception) { throw ApiError("Неверный адрес сервера") }
        try {
            c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 60000
            c.setRequestProperty("Accept", "application/json")
            if (edgeKey.isNotEmpty()) c.setRequestProperty("X-Crypto-Auth", edgeKey)
            if (apiKey.isNotEmpty()) {
                if (apiKey.startsWith("bsk_")) c.setRequestProperty("X-API-Key", apiKey)
                else c.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw ApiError(errorFor(code, text))
            return text
        } catch (e: ApiError) { throw e
        } catch (e: UnknownHostException) { throw ApiError("Нет связи: сервер не найден. Проверьте интернет и адрес.")
        } catch (e: ConnectException) { throw ApiError("Сервер недоступен (соединение отклонено).")
        } catch (e: SocketTimeoutException) { throw ApiError("Сервер не отвечает (таймаут).")
        } catch (e: IOException) { throw ApiError("Нет связи с сервером.")
        } finally { c.disconnect() }
    }

    fun get(path: String) = JSONObject(request("GET", path))

    fun health(): JSONObject = get("/api/health")
    fun summary() = get("/api/v1/watchlist/summary")
    fun portfolio() = get("/api/v1/evm/portfolio")
    fun watchlist() = get("/api/v1/watchlist?page=1&page_size=200")
    fun audit() = get("/api/v1/watchlist/audit?min_balance=0&page=1&page_size=200")
    fun liquidityStatus() = get("/api/v1/liquidity/status")
    fun signals() = get("/api/v1/liquidity/signals?limit=20")
    fun dormant() = get("/api/v1/liquidity/dormant")
    fun delete(id: String) = request("DELETE", "/api/v1/watchlist/" + URLEncoder.encode(id, "UTF-8"))
    fun add(address: String, chain: String?): JSONObject {
        val b = JSONObject().put("text", address.trim())
        if (!chain.isNullOrEmpty()) b.put("default_chain", chain)
        return JSONObject(request("POST", "/api/v1/import/watchlist", b.toString()))
    }

    companion object {
        fun errorFor(code: Int, body: String): String {
            val detail = try { JSONObject(body).optString("detail", "") } catch (e: Exception) { "" }
            return when (code) {
                401 -> "Неверный или отсутствующий API-ключ (401)."
                403 -> if (detail.contains("scope")) "У ключа нет прав на это действие (403). Ключ только для чтения."
                       else "Неверный ключ доступа (403)."
                404 -> "Сервер не знает такой функции (404)."
                422 -> "Сервер отклонил данные: " + detail.take(150)
                429 -> "Слишком много запросов, подождите минуту (429)."
                in 500..599 -> "Ошибка на сервере ($code). Попробуйте позже."
                else -> "Ошибка $code"
            }
        }
    }
}
