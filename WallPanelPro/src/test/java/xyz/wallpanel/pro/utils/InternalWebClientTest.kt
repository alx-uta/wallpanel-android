/*
 * Copyright (c) 2026 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.pro.utils

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.wallpanel.pro.ui.views.WebClientCallback

@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
class InternalWebClientTest {

    private var connected = true
    private val callback = mockk<WebClientCallback>(relaxed = true)
    private val view = mockk<WebView>(relaxed = true)
    private lateinit var client: InternalWebClient

    @Before
    fun setUp() {
        every { callback.isConnected } answers { connected }
        every { callback.isConnected = any() } answers { connected = firstArg() }
        every { callback.isFinishing() } returns false
        val context = ApplicationProvider.getApplicationContext<Context>()
        client = InternalWebClient(context.resources, callback, mockk(relaxed = true))
    }

    // What the WebView reports for a load that fails, up to the error page being shown
    private fun failLoad(url: String) {
        client.onPageStarted(view, url, null)
        client.onReceivedError(view, WebViewClient.ERROR_CONNECT, "net::ERR_CONNECTION_REFUSED", url)
        client.onPageFinished(view, url)
        client.onPageStarted(view, "about:blank", null)
        client.onPageFinished(view, "about:blank")
        client.onPageStarted(view, InternalWebClient.ERROR_PAGE, null)
        client.onPageFinished(view, InternalWebClient.ERROR_PAGE)
    }

    private fun load(url: String) {
        client.onPageStarted(view, url, null)
        client.onPageFinished(view, url)
    }

    @Test
    fun `a failed load and its error page leave the retry waiting`() {
        failLoad("http://192.168.8.246:9/unreachable")

        assertFalse(connected)
        verify(exactly = 1) { callback.startReloadDelay() }
        verify(exactly = 0) { callback.stopReloadDelay() }
    }

    @Test
    fun `a page that loads after an error ends the retry`() {
        failLoad("http://192.168.8.246:9/unreachable")
        load("http://192.168.8.246:8123/lovelace/0")

        assertTrue(connected)
        verify(exactly = 1) { callback.stopReloadDelay() }
    }
}
