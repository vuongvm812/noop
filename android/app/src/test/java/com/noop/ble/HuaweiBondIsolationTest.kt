package com.noop.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Huawei path never bonds and never reaches the WHOOP bond machinery (AGENTS.md, the `didBond` bullet):
 * the bond watchdog, the #982 never-bonded detector and the bond-refusal give-up all live in the WHOOP
 * client and its helpers, and none of the Huawei files may name them. A structural check, because the
 * Android GATT layer cannot run on the JVM.
 */
class HuaweiBondIsolationTest {

    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(userDir, File(userDir, ".."), File(userDir, "../.."))
            .firstOrNull { File(it, "android/app/src/main/java/com/noop/ble/HuaweiSource.kt").isFile }
            ?: error("could not locate the repo root from ${userDir.absolutePath}")
    }

    private val huaweiFiles = listOf("HuaweiSource.kt", "AndroidHuaweiGatt.kt", "HuaweiGattLink.kt", "HuaweiScanner.kt", "HuaweiKeyStore.kt")

    private fun code(name: String): String =
        File(repoRoot(), "android/app/src/main/java/com/noop/ble/$name").readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }
            .joinToString("\n")

    @Test fun noHuaweiFileBondsOrTouchesTheWhoopBondMachinery() {
        val banned = listOf(
            "createBond", "removeBond", "BOND_BONDED", "ACTION_BOND_STATE_CHANGED", "didBond",
            "WhoopBleClient", "BondWatchdog", "BondRefusalGiveUp", "ExplicitBond", "PostBondTimeout", "neverBonded",
        )
        for (file in huaweiFiles) {
            val text = code(file)
            for (word in banned) assertFalse("$file uses $word", text.contains(word))
        }
    }

    @Test fun theGattLayerConnectsOverLe() {
        assertTrue(code("AndroidHuaweiGatt.kt").contains("BluetoothDevice.TRANSPORT_LE"))
    }

    @Test fun theCoordinatorBuildsTheRealSourceForHuaweiRows() {
        val coordinator = File(repoRoot(), "android/app/src/main/java/com/noop/ble/SourceCoordinator.kt").readText()
        assertTrue(coordinator.contains("SourceKind.huawei.name -> makeHuaweiSource(id, ctx, row)"))
        assertFalse(coordinator.contains("HuaweiPlaceholderSource"))
    }
}
