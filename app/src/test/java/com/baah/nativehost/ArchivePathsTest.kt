package com.baah.nativehost

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArchivePathsTest {
    @Test fun archiveCannotEscapeDestination() {
        val directory = Files.createTempDirectory("neo-archive-test").toFile()
        try {
            assertEquals(File(directory, "configs/jp.json").canonicalPath, RuntimeHost.child(directory, "configs/jp.json").canonicalPath)
            listOf("../outside", "configs/../../outside", File(directory.parentFile, "outside").absolutePath).forEach { name ->
                try { RuntimeHost.child(directory, name); fail("accepted $name") } catch (_: IllegalArgumentException) { }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun configurationNamesCannotBecomePathsOrShellSyntax() {
        listOf("jp", "cn-1", "daily_v2").forEach { assertTrue(UserData.validName(it)) }
        listOf("", "../jp", "jp.json", "a b", "a;id", "x/y").forEach { assertFalse(UserData.validName(it)) }
    }
}
