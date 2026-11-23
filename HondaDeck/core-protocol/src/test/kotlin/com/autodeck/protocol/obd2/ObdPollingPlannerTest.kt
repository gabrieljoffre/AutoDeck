package com.autodeck.protocol.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPollingPlannerTest {

    @Test
    fun `HOME_TURBO emits exactly 3 commands per cycle and covers core monitoring PIDs every 5 cycles`() {
        val capabilities = SupportedPidCapabilities(supportsMap = true, supportsMaf = false)
        val allSeen = mutableSetOf<String>()

        for (cycle in 0L until 5L) {
            val cmds = ObdPollingPlanner.nextCommands(
                profile = ObdPollingProfile.HOME_TURBO,
                cycleIndex = cycle,
                capabilities = capabilities
            )
            assertEquals(3, cmds.size)
            assertEquals(ObdPid.SPEED.command, cmds[0])
            assertEquals(ObdPid.RPM.command, cmds[1])
            allSeen.addAll(cmds)
        }

        assertTrue(allSeen.contains(ObdPid.SPEED.command))
        assertTrue(allSeen.contains(ObdPid.RPM.command))
        assertTrue(allSeen.contains(ObdPid.ENGINE_LOAD.command))
        assertTrue(allSeen.contains(ObdPid.INTAKE_MAP.command))
        assertTrue(allSeen.contains("ATRV"))
        assertTrue(allSeen.contains(ObdPid.COOLANT_TEMP.command))
        assertTrue(allSeen.contains(ObdPid.INTAKE_TEMP.command))
    }
}
