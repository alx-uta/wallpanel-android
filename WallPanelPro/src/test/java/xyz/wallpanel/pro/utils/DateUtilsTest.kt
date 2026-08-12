/*
 * Copyright (c) 2022 WallPanel
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
import org.junit.Test

/**
 * Covers the time-picker parsing used by the screensaver and dim schedules. These run on
 * the JVM with no emulator.
 */
class DateUtilsTest {

    @Test
    fun `padTimePickerOutput pads a single digit`() {
        assertEquals("07", DateUtils.padTimePickerOutput("7"))
    }

    @Test
    fun `padTimePickerOutput leaves two digits alone`() {
        assertEquals("07", DateUtils.padTimePickerOutput("07"))
        assertEquals("23", DateUtils.padTimePickerOutput("23"))
    }

    @Test
    fun `getHourFromTimePicker reads the hour`() {
        assertEquals(23, DateUtils.getHourFromTimePicker("23:45"))
        assertEquals(0, DateUtils.getHourFromTimePicker("00:30"))
    }

    @Test
    fun `getMinutesFromTimePicker reads the minutes`() {
        assertEquals(45, DateUtils.getMinutesFromTimePicker("23:45"))
        assertEquals(0, DateUtils.getMinutesFromTimePicker("23:00"))
    }

    @Test
    fun `time picker getters fall back to zero without a separator`() {
        assertEquals(0, DateUtils.getHourFromTimePicker("2345"))
        assertEquals(0, DateUtils.getMinutesFromTimePicker("2345"))
    }

    @Test
    fun `getHourAndMinutesFromTimePicker converts to a decimal`() {
        assertEquals(23.45f, DateUtils.getHourAndMinutesFromTimePicker("23:45"), 0.001f)
    }

    @Test
    fun `convertInactivityTime reports seconds below a minute`() {
        assertEquals("30", DateUtils.convertInactivityTime(30_000L))
    }

    @Test
    fun `convertInactivityTime reports minutes between a minute and half an hour`() {
        assertEquals("5", DateUtils.convertInactivityTime(300_000L))
    }

    @Test
    fun `convertInactivityTime reports hours above half an hour`() {
        assertEquals("1", DateUtils.convertInactivityTime(3_600_000L))
    }
}
