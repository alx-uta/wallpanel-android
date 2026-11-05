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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import timber.log.Timber
import java.io.File

class LogFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun lines(file: LogFile) = file.lines()

    @Test
    fun `an empty log has no lines`() {
        assertTrue(lines(LogFile(File(folder.root, "new"))).isEmpty())
    }

    @Test
    fun `lines come back oldest first across a rotation`() {
        val file = LogFile(folder.root, maxBytes = 10)
        listOf("one\n", "two\n", "three\n").forEach { file.append(it) }
        assertEquals(listOf("one", "two", "three"), lines(file))
    }

    @Test
    fun `the oldest lines go and the log stays bounded`() {
        val file = LogFile(folder.root, maxBytes = 100)
        repeat(1000) { file.append("line $it\n") }
        val kept = lines(file)
        assertEquals("line 999", kept.last())
        assertFalse("line 0" in kept)
        assertTrue(folder.root.listFiles()!!.sumOf { it.length() } <= 200)
    }

    private fun tree(hidden: List<String> = emptyList()): Pair<LogFile, LogFileTree> {
        val file = LogFile(folder.root)
        return file to LogFileTree(file, { hidden }, { "" })
    }

    @Test
    fun `debug lines stay out and the rest are written with who logged them`() {
        val (file, tree) = tree()
        tree.d("noise")
        tree.i("hello")
        tree.w("careful")
        tree.e("broken")
        tree.flush()
        val kept = lines(file)
        assertEquals(3, kept.size)
        assertTrue(kept[0], kept[0].endsWith(" I LogFileTest: hello"))
        assertTrue(kept[1], kept[1].endsWith(" W LogFileTest: careful"))
        assertTrue(kept[2], kept[2].endsWith(" E LogFileTest: broken"))
    }

    @Test
    fun `lines are redacted before they reach the disk`() {
        val (file, tree) = tree(listOf("hunter2"))
        tree.i("the password is hunter2")
        tree.flush()
        val text = lines(file).joinToString()
        assertFalse(text.contains("hunter2"))
    }

    @Test
    fun `an exception keeps its stack trace and a repeated line is counted`() {
        val (file, tree) = tree()
        tree.e(IllegalStateException("boom"), "failed")
        repeat(5) { tree.w("same") }
        tree.i("different")
        tree.flush()
        val kept = lines(file)
        assertTrue(kept.any { it.contains("java.lang.IllegalStateException: boom") })
        assertEquals(1, kept.count { it.endsWith(": same") })
        assertTrue(kept.any { it.endsWith("repeated 4 more times") })
        assertEquals(kept.map { it.take(23) }.filter { it.startsWith("20") }, kept.map { it.take(23) }.filter { it.startsWith("20") }.sorted())
        assertTrue(kept.last().endsWith(": different"))
    }

    @Test
    fun `a line logged after a flush is written even when it repeats the last`() {
        val (file, tree) = tree()
        tree.i("same")
        tree.flush()
        tree.i("same")
        tree.flush()
        assertEquals(2, lines(file).count { it.endsWith(": same") })
    }

    @Test
    fun `a flood loses lines without blocking and says so`() {
        val (file, tree) = tree()
        repeat(2_500) { tree.i("line $it") }
        tree.flush()
        val kept = lines(file)
        assertEquals(2_001, kept.size)
        assertTrue(kept.last(), kept.last().contains("Lost 500 lines"))
    }

    @Test
    fun `a long message keeps both ends and is redacted before it is cut`() {
        val (file, tree) = tree(listOf("hunter2"))
        tree.e("start " + "x".repeat(3_996) + "hunter2" + "y".repeat(10_000) + " Caused by: the cause")
        tree.flush()
        val text = lines(file).joinToString("\n")
        assertTrue(text.contains(" start "))
        assertTrue(text.endsWith("Caused by: the cause"))
        assertTrue(text.contains("characters cut"))
        assertFalse(text.contains("hunte"))
    }

    @Test
    fun `a batch bigger than the file limit still rotates`() {
        val file = LogFile(folder.root, maxBytes = 200_000)
        val tree = LogFileTree(file, { emptyList() }, { "" })
        repeat(100) { tree.i("$it " + "x".repeat(7_000)) }
        tree.flush()
        assertEquals(2, folder.root.listFiles()!!.size)
        assertTrue(folder.root.listFiles()!!.all { it.length() <= 200_000 })
    }

    @Test
    fun `a failed rotation does not let the file grow`() {
        val file = LogFile(folder.root, maxBytes = 100)
        File(folder.root, "wallpanel.log.1").mkdirs()
        File(folder.root, "wallpanel.log.1/keep").writeText("in the way")
        repeat(100) { file.append("line $it\n") }
        assertTrue(File(folder.root, "wallpanel.log").length() <= 100)
    }

    @Test
    fun `the tree works planted in Timber`() {
        val (file, tree) = tree()
        Timber.plant(tree)
        try {
            Timber.i("through %s", "Timber")
            tree.flush()
        } finally {
            Timber.uproot(tree)
        }
        assertTrue(lines(file).single().endsWith(" I LogFileTest: through Timber"))
    }
}
