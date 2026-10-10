package org.pianobarsuper.app

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pianobarsuper.app.net.json
import org.pianobarsuper.app.update.ServerApk
import org.pianobarsuper.app.update.UpdatePolicy
import java.io.File

class UpdatePolicyTest {
    private val sha = "975e7c23158e28f2ed4161b1672e3f011fd1b5e9ecfbb501bc9333b99596c863"
    private val apk = ServerApk("org.pianobarsuper.app", 6, "2026.10.10.1", sha, 22_000_000)

    @Test fun parsesTheServerAnswer() {
        val parsed = json.decodeFromString<ServerApk>("""{"package":"org.pianobarsuper.app","versionCode":6,"versionName":"2026.10.10.1",
            "sha256":"$sha","size":22000000,"updated":1791647977,"url":"/pianobar.apk","later":true}""")
        assertEquals(apk, parsed)
    }

    @Test fun onlyANewerBuildOfThisAppIsAnUpdate() {
        assertTrue(UpdatePolicy.isNewer(apk, "org.pianobarsuper.app", 5))
        assertFalse(UpdatePolicy.isNewer(apk, "org.pianobarsuper.app", 6))
        assertFalse(UpdatePolicy.isNewer(apk, "org.pianobarsuper.app", 7))
        assertFalse(UpdatePolicy.isNewer(apk.copy(packageName = "org.example.other"), "org.pianobarsuper.app", 5))
        assertFalse(UpdatePolicy.isNewer(apk.copy(sha256 = ""), "org.pianobarsuper.app", 5))
        assertFalse(UpdatePolicy.isNewer(apk.copy(sha256 = sha.uppercase()), "org.pianobarsuper.app", 5))
        assertFalse(UpdatePolicy.isNewer(apk.copy(size = 0), "org.pianobarsuper.app", 5))
        assertFalse(UpdatePolicy.isNewer(apk.copy(size = UpdatePolicy.MAX_SIZE + 1), "org.pianobarsuper.app", 5))
    }

    @Test fun signersMustMatchExactly() {
        assertTrue(UpdatePolicy.sameSigners(listOf("a"), listOf("a")))
        assertTrue(UpdatePolicy.sameSigners(listOf("a", "b"), listOf("b", "a")))
        assertFalse(UpdatePolicy.sameSigners(listOf("a"), listOf("b")))
        assertFalse(UpdatePolicy.sameSigners(listOf("a"), listOf("a", "b")))
        assertFalse(UpdatePolicy.sameSigners(listOf("a"), emptyList()))
        assertFalse(UpdatePolicy.sameSigners(emptyList(), emptyList()))
    }

    @Test fun hashesFiles() {
        val file = File.createTempFile("update", ".apk").apply { deleteOnExit(); writeBytes("abc".toByteArray()) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdatePolicy.sha256(file))
        assertEquals(UpdatePolicy.sha256("abc".toByteArray()), UpdatePolicy.sha256(file))
    }

    @Test fun rootCommandQuotesThePath() {
        val command = UpdatePolicy.rootInstallCommand(File("/data/user/0/org.pianobarsuper.app/cache/up dates/it's.apk"), "org.pianobarsuper.app")
        assertEquals("pm install -r -i 'org.pianobarsuper.app' '/data/user/0/org.pianobarsuper.app/cache/up dates/it'\\''s.apk'", command)
    }

    @Test fun rootResultNeedsSuccess() {
        assertTrue(UpdatePolicy.rootInstallSucceeded("Performing Streamed Install\nSuccess\n"))
        assertFalse(UpdatePolicy.rootInstallSucceeded("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]"))
        assertFalse(UpdatePolicy.rootInstallSucceeded("rsu: lock timeout"))
        assertFalse(UpdatePolicy.rootInstallSucceeded(""))
    }

    @Test fun unattendedInstallsWaitUntilNobodyIsUsingTheApp() {
        assertTrue(UpdatePolicy.mayInstallUnattended(playing = false, appVisible = false, alreadyTried = false))
        // Launching the app must not close it under the user a few seconds later.
        assertFalse(UpdatePolicy.mayInstallUnattended(playing = false, appVisible = true, alreadyTried = false))
        assertFalse(UpdatePolicy.mayInstallUnattended(playing = true, appVisible = false, alreadyTried = false))
        assertFalse(UpdatePolicy.mayInstallUnattended(playing = false, appVisible = false, alreadyTried = true))
    }

    @Test fun unattendedDownloadsWaitForAnUnmeteredNetwork() {
        assertTrue(UpdatePolicy.mayDownloadUnattended(metered = false))
        assertFalse(UpdatePolicy.mayDownloadUnattended(metered = true))
    }
}
