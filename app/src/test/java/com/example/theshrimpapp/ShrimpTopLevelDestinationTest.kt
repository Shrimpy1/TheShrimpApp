package com.example.theshrimpapp

import com.example.theshrimpapp.core.navigation.ShrimpTopLevelDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ShrimpTopLevelDestinationTest {

    @Test
    fun topLevelDestinations_areNotNull() {
        val destinations = ShrimpTopLevelDestination.ALL
        assertNotNull(destinations)
        assertEquals(3, destinations.size)
        destinations.forEach { destination ->
            assertNotNull("Destination in ALL should not be null", destination)
            assertNotNull("Destination route should not be null", destination.route)
        }
    }

    @Test
    fun topLevelDestinations_haveCorrectRoutes() {
        assertEquals("home", ShrimpTopLevelDestination.Home.route)
        assertEquals("image_optimizer", ShrimpTopLevelDestination.ImageOptimizer.route)
        assertEquals("files", ShrimpTopLevelDestination.Files.route)
    }

    @Test
    fun fromRoute_mapsRoutesToTopLevelDestinations() {
        assertEquals(ShrimpTopLevelDestination.Home, ShrimpTopLevelDestination.fromRoute("home"))
        assertEquals(ShrimpTopLevelDestination.ImageOptimizer, ShrimpTopLevelDestination.fromRoute("image_optimizer"))
        assertEquals(ShrimpTopLevelDestination.ImageOptimizer, ShrimpTopLevelDestination.fromRoute("image_optimizer_output"))
        assertEquals(ShrimpTopLevelDestination.ImageOptimizer, ShrimpTopLevelDestination.fromRoute("image_optimizer_processing"))
        assertEquals(ShrimpTopLevelDestination.ImageOptimizer, ShrimpTopLevelDestination.fromRoute("image_optimizer_result"))
        assertEquals(ShrimpTopLevelDestination.Files, ShrimpTopLevelDestination.fromRoute("files"))
        assertEquals(ShrimpTopLevelDestination.Home, ShrimpTopLevelDestination.fromRoute("unknown_route"))
    }
}
