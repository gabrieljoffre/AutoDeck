package com.autodeck.headunit

import com.autodeck.headunit.navigation.InAppRouteNavigator
import com.autodeck.headunit.navigation.ManeuverIcon
import com.autodeck.headunit.navigation.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppRouteNavigatorTest {

    @Test
    fun `manobras OSRM viram instrucoes em portugues`() {
        assertEquals("Vire à direita", InAppRouteNavigator.instructionPt("turn", "right", 0))
        assertEquals("Na rotatória, pegue a 2ª saída", InAppRouteNavigator.instructionPt("roundabout", "right", 2))
        assertEquals("Você chegou ao destino", InAppRouteNavigator.instructionPt("arrive", "", 0))
        assertEquals(ManeuverIcon.SLIGHT_LEFT, InAppRouteNavigator.iconFor("turn", "slight left"))
        assertEquals(ManeuverIcon.ROUNDABOUT, InAppRouteNavigator.iconFor("rotary", "left"))
    }

    @Test
    fun `progresso usa o vertice mais proximo da rota`() {
        val poly = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.0, 0.002))
        val cum = InAppRouteNavigator.cumulativeDistances(poly)
        assertEquals(0.0, cum[0], 0.001)
        assertEquals(222.4, cum[2], 1.0)
        val (idx, dist) = InAppRouteNavigator.nearestVertex(poly, RoutePoint(0.0, 0.0019))
        assertEquals(2, idx)
        assertTrue(dist < 15.0)
    }
}
