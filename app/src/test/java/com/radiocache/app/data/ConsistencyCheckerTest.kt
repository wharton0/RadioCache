package com.radiocache.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConsistencyCheckerTest {

    private fun tmp(name: String, bytes: ByteArray?): String {
        val f = File(System.getProperty("java.io.tmpdir"), "rc-$name-${System.nanoTime()}")
        if (bytes != null) f.writeBytes(bytes)
        return f.absolutePath
    }

    @Test fun `a real file with bytes is healthy`() {
        val p = tmp("ok", ByteArray(2048) { 1 })
        try {
            assertTrue(ConsistencyChecker.isHealthy(p))
        } finally {
            File(p).delete()
        }
    }

    @Test fun `a missing file is not healthy`() {
        assertFalse(ConsistencyChecker.isHealthy("/definitely/not/here/x.m4a"))
    }

    @Test fun `a zero-length file is not healthy`() {
        // The interrupted-download case: the file exists but is truncated.
        val p = tmp("empty", ByteArray(0))
        try {
            assertFalse(ConsistencyChecker.isHealthy(p))
        } finally {
            File(p).delete()
        }
    }

    @Test fun `a directory is not healthy`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rc-dir-${System.nanoTime()}")
        dir.mkdirs()
        try {
            assertFalse(ConsistencyChecker.isHealthy(dir.absolutePath))
        } finally {
            dir.delete()
        }
    }

    @Test fun `null and blank paths are not healthy`() {
        assertFalse(ConsistencyChecker.isHealthy(null))
        assertFalse(ConsistencyChecker.isHealthy(""))
        assertFalse(ConsistencyChecker.isHealthy("   "))
    }
}