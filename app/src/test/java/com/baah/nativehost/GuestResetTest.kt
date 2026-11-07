package com.baah.nativehost

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files

class GuestResetTest {
    @Test fun rebuildingRemovesOldPackagesAndKeepsSiblingUserData() {
        val workspace = Files.createTempDirectory("neo-reset-test").toFile()
        try {
            val root = workspace.resolve("rootfs").apply { mkdir() }
            root.resolve("usr/local/lib/old-package/module.py").apply { parentFile.mkdirs(); writeText("obsolete") }
            root.resolve(".baah-environment-ready").writeText("1")
            val user = workspace.resolve("baah-user").apply { mkdir() }.resolve("config.json").apply { writeText("preserve") }
            RuntimeHost.clearGuestRoot(root)
            assertTrue(root.isDirectory)
            assertEquals(0, root.listFiles()!!.size)
            assertEquals("preserve", user.readText())
            RuntimeHost.clearGuestRoot(root)
            assertTrue(root.isDirectory)
        } finally { workspace.deleteRecursively() }
    }

    @Test fun guestSymlinkNeverDeletesItsExternalTarget() {
        val workspace = Files.createTempDirectory("neo-reset-link-test").toFile()
        try {
            val root = workspace.resolve("rootfs").apply { mkdir() }
            val outside = workspace.resolve("user").apply { mkdir() }
            val file = outside.resolve("config.json").apply { writeText("preserve") }
            try { Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath()) }
            catch (e: java.io.IOException) { assumeNoException(e); return }
            RuntimeHost.clearGuestRoot(root)
            assertEquals("preserve", file.readText())
            assertEquals(0, root.listFiles()!!.size)
        } finally { workspace.deleteRecursively() }
    }
}
