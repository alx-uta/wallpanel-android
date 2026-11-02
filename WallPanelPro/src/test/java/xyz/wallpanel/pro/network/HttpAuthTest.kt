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

package xyz.wallpanel.pro.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HttpAuthTest {

    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    @Test
    fun `every request goes through while no token is set`() {
        assertTrue(HttpAuth.isAuthorized("", null, null))
        assertTrue(HttpAuth.isAuthorized("", "Bearer anything", "wrong"))
    }

    @Test
    fun `a request without the token is refused once one is set`() {
        assertFalse(HttpAuth.isAuthorized("s3cret", null, null))
        assertFalse(HttpAuth.isAuthorized("s3cret", "", ""))
    }

    @Test
    fun `a bearer token is accepted`() {
        assertTrue(HttpAuth.isAuthorized("s3cret", "Bearer s3cret", null))
        assertTrue(HttpAuth.isAuthorized("s3cret", "bearer   s3cret ", null))
    }

    @Test
    fun `a wrong bearer token is refused`() {
        assertFalse(HttpAuth.isAuthorized("s3cret", "Bearer s3cre", null))
        assertFalse(HttpAuth.isAuthorized("s3cret", "Bearer s3cret2", null))
        assertFalse(HttpAuth.isAuthorized("s3cret", "Bearers3cret", null))
    }

    @Test
    fun `basic authentication takes the token as the password with any user`() {
        assertTrue(HttpAuth.isAuthorized("s3cret", basic("homeassistant", "s3cret"), null))
        assertTrue(HttpAuth.isAuthorized("s3cret", basic("", "s3cret"), null))
        assertFalse(HttpAuth.isAuthorized("s3cret", basic("s3cret", "nope"), null))
    }

    @Test
    fun `a password holding a colon is kept whole`() {
        assertTrue(HttpAuth.isAuthorized("a:b:c", basic("user", "a:b:c"), null))
    }

    @Test
    fun `the token query parameter is accepted`() {
        assertTrue(HttpAuth.isAuthorized("s3cret", null, "s3cret"))
        assertFalse(HttpAuth.isAuthorized("s3cret", null, "S3cret"))
    }

    @Test
    fun `a right query parameter is enough next to a wrong header`() {
        assertTrue(HttpAuth.isAuthorized("s3cret", "Bearer wrong", "s3cret"))
    }

    @Test
    fun `a token outside ASCII compares as text`() {
        assertTrue(HttpAuth.isAuthorized("pässwörd", "Bearer pässwörd", null))
    }

    @Test
    fun `credentials come out of the headers that carry them only`() {
        assertEquals("abc", HttpAuth.credentialFrom("Bearer abc"))
        assertEquals("pw", HttpAuth.credentialFrom(basic("u", "pw")))
        assertNull(HttpAuth.credentialFrom("Digest username=x"))
        assertNull(HttpAuth.credentialFrom("Basic !!!not-base64!!!"))
        assertNull(HttpAuth.credentialFrom("Basic " + Base64.getEncoder().encodeToString("nocolon".toByteArray())))
        assertNull(HttpAuth.credentialFrom("Bearer"))
        assertNull(HttpAuth.credentialFrom(null))
    }
}
