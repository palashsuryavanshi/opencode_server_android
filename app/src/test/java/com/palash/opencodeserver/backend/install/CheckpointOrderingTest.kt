package com.palash.opencodeserver.backend.install

import com.palash.opencodeserver.backend.core.SetupCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 99/100 - setup checkpoints.
 *
 * The resume guarantee is: if a run stopped at NODE_READY, the next run starts at OPENCODE_READY.
 * That only works if the checkpoint enum is ordered, which is what these tests lock in.
 */
class CheckpointOrderingTest {

    @Test
    fun checkpointsAreDeclaredInInstallationOrder() {
        val declared = SetupCheckpoint.entries

        assertEquals(
            listOf(
                SetupCheckpoint.STARTED,
                SetupCheckpoint.RUNTIME_READY,
                SetupCheckpoint.PACKAGE_MANAGER_READY,
                SetupCheckpoint.GIT_READY,
                SetupCheckpoint.NODE_READY,
                SetupCheckpoint.OPENCODE_READY,
                SetupCheckpoint.CONFIG_READY,
                SetupCheckpoint.SERVER_READY,
                SetupCheckpoint.COMPLETE
            ),
            declared
        )
    }

    @Test
    fun runtimeCheckpointPrecedesOpenCodeCheckpoint() {
        assertTrue(SetupCheckpoint.RUNTIME_READY < SetupCheckpoint.OPENCODE_READY)
    }

    @Test
    fun nodeCheckpointPrecedesOpenCodeCheckpoint() {
        assertTrue(SetupCheckpoint.NODE_READY < SetupCheckpoint.OPENCODE_READY)
        assertTrue(SetupCheckpoint.GIT_READY < SetupCheckpoint.NODE_READY)
    }

    @Test
    fun openCodeCheckpointPrecedesConfigCheckpoint() {
        assertTrue(SetupCheckpoint.OPENCODE_READY < SetupCheckpoint.CONFIG_READY)
    }

    @Test
    fun completeIsTheFinalCheckpoint() {
        assertEquals(SetupCheckpoint.COMPLETE, SetupCheckpoint.entries.last())
    }

    @Test
    fun everyCheckpointHasAUserFacingLabel() {
        SetupCheckpoint.entries.forEach { checkpoint ->
            assertTrue(
                "$checkpoint needs a label for the resume message",
                checkpoint.stageLabel.isNotBlank()
            )
        }
    }

    @Test
    fun labelsAvoidRawInternalNames() {
        // Spec 97: the resume message is normal-UI text, not an enum name.
        SetupCheckpoint.entries.forEach { checkpoint ->
            assertTrue(
                "${checkpoint.name} should not leak its enum name",
                checkpoint.stageLabel != checkpoint.name
            )
        }
    }
}