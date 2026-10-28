package com.cryptosuite.simple

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File

/** Renders README screenshots with FAKE example data (run: ./gradlew testDebugUnitTest -Pscreenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class ScreenshotTest {
    private val srv = MockWebServer()
    private val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val A1 = "0x000000000000000000000000000000000000dEaD"
    private val A2 = "0x1111111111111111111111111111111111111111"
    private val B1 = "bc1qexampleexampleexampleexampleexample00"
    private val L1 = "ltc1qexampleexampleexampleexampleexample0"

    @Before fun start() {
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path ?: ""
                val body = when {
                    p.startsWith("/api/health") -> """{"status":"ok","version":"example","db":"ok","uptime_s":86400}"""
                    p.startsWith("/api/v1/watchlist/summary") -> """{"totals":{"addresses":4},"per_chain":{
                        "btc":{"count":1,"balance_available":true,"balance_sat":12345678},
                        "ltc":{"count":1,"balance_available":true,"balance_sat":250000000},
                        "bch":{"count":0,"balance_available":true,"balance_sat":0}}}"""
                    p.startsWith("/api/v1/evm/portfolio") -> """{"accounts":[{"account":{"address":"$A1"},"balances":[
                        {"chain_id":1,"native_symbol":"ETH","native_wei":"420000000000000000","tokens":[
                          {"symbol":"USDT","address":"0xa","decimals":6,"balance":"150000000"},
                          {"symbol":"USDC","address":"0xb","decimals":6,"balance":"75500000"}]},
                        {"chain_id":56,"native_symbol":"BNB","native_wei":"1200000000000000000","tokens":[]},
                        {"chain_id":137,"native_symbol":"MATIC","native_wei":"33000000000000000000","tokens":[]}]}]}"""
                    p.startsWith("/api/v1/watchlist/audit") -> """{"scanned":4,"items":[
                        {"chain":"evm","address":"$A1","balance_available":true,"balance_display":"0.42 ETH"},
                        {"chain":"evm","address":"$A2","balance_available":true,"balance_display":"0 ETH"},
                        {"chain":"btc","address":"$B1","balance_available":true,"balance_display":"0.12345678 BTC"},
                        {"chain":"ltc","address":"$L1","balance_available":false}],"notes":["Пример данных (демо)."]}"""
                    p.startsWith("/api/v1/watchlist") -> """{"total":4,"items":[
                        {"id":"1","chain":"evm","address":"$A1","label":"Пример"},
                        {"id":"2","chain":"evm","address":"$A2","label":null},
                        {"id":"3","chain":"btc","address":"$B1","label":"Демо BTC"},
                        {"id":"4","chain":"ltc","address":"$L1","label":null}]}"""
                    p.startsWith("/api/v1/liquidity/status") -> """{"tracked":4,"dormant":1,"unavailable":0}"""
                    p.startsWith("/api/v1/liquidity/signals") -> """{"items":[
                        {"chain":"evm","address":"$A1","direction":"in","asset":"ETH","created_at":"2026-01-01T12:00:00Z"},
                        {"chain":"btc","address":"$B1","direction":"out","asset":"BTC","created_at":"2026-01-01T09:30:00Z"}]}"""
                    p.startsWith("/api/v1/liquidity/dormant") -> """{"items":[{"chain":"evm","address":"$A2"}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        srv.start(); out.mkdirs()
    }
    @After fun stop() = srv.shutdown()

    private fun configure(on: Boolean) {
        val s = Store(RuntimeEnvironment.getApplication()); s.clear(); s.rootWarned = true
        if (on) { s.url = srv.url("/").toString().trimEnd('/')
            s.edgeKey = "example-edge-key"; s.apiKey = "bsk_example_readonly" }
    }
    private fun settle() { repeat(15) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() } }
    private fun shot(a: MainActivity, name: String) {
        val v = a.window.decorView
        val w = 720; val h = 1480
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); c.drawColor(0xFFFFFFFF.toInt()); v.draw(c)
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun find(v: View, txt: String): MaterialButton? = when {
        v is MaterialButton && v.text.toString() == txt -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it), txt) }
        else -> null }

    private fun findNav(v: View): BottomNavigationView? = if (v is BottomNavigationView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { findNav(g.getChildAt(it)) } }

    @Test fun render() {
        configure(false)
        Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle(); shot(it, "0_setup") }
        configure(true)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); settle()
        val nav = findNav(a.window.decorView)!!
        listOf(0 to "1_balance", 1 to "2_addresses", 2 to "3_audit", 3 to "4_status", 4 to "5_settings").forEach { (i, n) ->
            nav.selectedItemId = i; settle()
            if (i == 2) { find(a.window.decorView, "Запустить аудит")?.performClick(); settle() }
            shot(a, n)
        }
    }
}
