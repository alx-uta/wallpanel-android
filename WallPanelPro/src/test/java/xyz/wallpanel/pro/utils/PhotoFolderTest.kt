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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFolderTest {

    @Test
    fun `only images count as photos`() {
        assertTrue(PhotoFolder.isPhoto("image/jpeg"))
        assertTrue(PhotoFolder.isPhoto("image/png"))
        assertFalse(PhotoFolder.isPhoto("video/mp4"))
        assertFalse(PhotoFolder.isPhoto("vnd.android.document/directory"))
        assertFalse(PhotoFolder.isPhoto(null))
    }

    @Test
    fun `a folder is named by its path, the top of a volume by the volume`() {
        assertEquals("Pictures/Family", PhotoFolder.displayName("primary:Pictures/Family"))
        assertEquals("primary", PhotoFolder.displayName("primary:"))
        assertEquals("12AB-34CD", PhotoFolder.displayName("12AB-34CD:"))
        assertEquals("downloads", PhotoFolder.displayName("downloads"))
    }

    @Test
    fun `a pass shows each photo once and then asks for a new listing`() {
        val rotation = PhotoRotation<String>()
        rotation.start(listOf("a", "b", "c"))
        val shown = listOf(rotation.next(), rotation.next(), rotation.next())
        assertEquals(setOf("a", "b", "c"), shown.toSet())
        assertNull(rotation.next())
    }

    @Test
    fun `an empty folder has nothing to show`() {
        val rotation = PhotoRotation<String>()
        rotation.start(emptyList())
        assertTrue(rotation.isEmpty)
        assertNull(rotation.next())
    }

    @Test
    fun `a new pass does not open with the photo on screen`() {
        // Keeps the listed order, so the last photo of a pass would come first after a reversal
        val rotation = PhotoRotation<String> { it.reverse() }
        rotation.start(listOf("b", "a"))
        assertEquals("a", rotation.next())
        assertEquals("b", rotation.next())
        rotation.start(listOf("a", "b"))
        assertNotEquals("b", rotation.next())
    }

    @Test
    fun `a single photo can follow itself`() {
        val rotation = PhotoRotation<String>()
        rotation.start(listOf("a"))
        assertEquals("a", rotation.next())
        rotation.start(listOf("a"))
        assertEquals("a", rotation.next())
    }
}
