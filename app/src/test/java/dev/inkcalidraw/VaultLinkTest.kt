package dev.inkcalidraw

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultLinkTest {
    private val link = VaultLink("Uni", "Lectures/Week 3.excalidraw", "/storage/emulated/0/Documents/Uni")

    @Test fun vaultFolderGranted() =
        assertEquals("Lectures/Week 3.excalidraw", VaultResolver.candidates("/storage/emulated/0/Documents/Uni", link, "Uni").first())

    @Test fun parentFolderGranted() =
        assertEquals("Uni/Lectures/Week 3.excalidraw", VaultResolver.candidates("/storage/emulated/0/Documents", link, "Documents").first())

    @Test fun wholeStorageGranted() =
        assertEquals("Documents/Uni/Lectures/Week 3.excalidraw", VaultResolver.candidates("/storage/emulated/0", link, "0").first())

    @Test fun sdcardAlias() = assertEquals(
        "Documents/Uni/Lectures/Week 3.excalidraw",
        VaultResolver.candidates("/storage/emulated/0", link.copy(abs = "/sdcard/Documents/Uni"), null).first())

    @Test fun withoutAbsolutePathFallsBackToVaultName() {
        val l = link.copy(abs = null)
        assertEquals(listOf("Lectures/Week 3.excalidraw", "Uni/Lectures/Week 3.excalidraw"), VaultResolver.candidates(null, l, "Uni"))
        assertEquals(listOf("Uni/Lectures/Week 3.excalidraw"), VaultResolver.candidates(null, l, "Documents"))
    }

    @Test fun unrelatedFolderGivesNoPathUnderIt() =
        assertEquals(listOf("Uni/Lectures/Week 3.excalidraw"), VaultResolver.candidates("/storage/emulated/0/Download", link, "Download"))
}
