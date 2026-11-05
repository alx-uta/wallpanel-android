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

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class LockdownTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `allowed apps are package names separated any reasonable way`() {
        assertEquals(
            listOf("com.spotify.music", "org.videolan.vlc", "com.example.app_2"),
            Lockdown.parseApps(" com.spotify.music,org.videolan.vlc\ncom.example.app_2; com.spotify.music")
        )
    }

    @Test
    fun `things that are not package names are left out`() {
        assertEquals(emptyList<String>(), Lockdown.parseApps(""))
        assertEquals(listOf("a.b"), Lockdown.parseApps("spotify, 1com.x, com., a.b, com..x, https://x.y"))
    }

    @Test
    fun `screen off and reboot follow device admin and owner`() {
        assertFalse(Lockdown.canTurnScreenOff(context))
        assertFalse(Lockdown.screenOff(context))
        assertFalse(Lockdown.canReboot(context))

        val policy = Shadows.shadowOf(context.getSystemService(DevicePolicyManager::class.java))
        policy.setActiveAdmin(Lockdown.admin(context))
        assertTrue(Lockdown.canTurnScreenOff(context))
        assertFalse(Lockdown.canReboot(context))

        policy.setDeviceOwner(Lockdown.admin(context))
        assertTrue(Lockdown.isDeviceOwner(context))
        assertTrue(Lockdown.canReboot(context))
    }
}
