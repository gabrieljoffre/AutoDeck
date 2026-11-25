package com.autodeck.hondadeck.ponte

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PonteAccessibilityWindowTest {

    @Test
    fun `janela de acessibilidade so fica ativa dentro dos 15 segundos e desarma ao concluir`() {
        PonteAccessibilityController.disarm()
        assertNull(PonteAccessibilityController.currentActive(nowElapsedMs = 1_000L))

        val pending = PonteAccessibilityController.PendingAction(
            mode = PonteAccessibilityController.ActionMode.SEND_TEXT,
            armedAtElapsedMs = 10_000L,
            expiresAtElapsedMs = 25_000L
        )
        assertTrue(pending.isActive(10_000L))
        assertTrue(pending.isActive(24_999L))
        assertTrue(pending.isActive(25_000L))
        assertFalse(pending.isActive(25_001L))
        assertFalse(pending.isActive(9_999L))

        pending.completion.complete(true)
        assertFalse(pending.isActive(15_000L))
    }

    @Test
    fun `armar e desarmar controla estado global`() {
        val action = PonteAccessibilityController.arm(PonteAccessibilityController.ActionMode.SEND_VOICE, windowMs = 15_000L)
        assertNotNull(PonteAccessibilityController.currentActive(action.armedAtElapsedMs + 100L))
        PonteAccessibilityController.markCompleted(action, true)
        assertNull(PonteAccessibilityController.currentActive(action.armedAtElapsedMs + 200L))
    }
}
