package space.linuxct.glyphworks.ui.pipeline

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.pipeline.PipelineSimulation
import space.linuxct.pipeline.*

class PipelineSimulationInputTest {
    private fun project(): PipelineDocument {
        val program = Program(id = "main", name = "Clock check", variables = listOf(Variable("hour", "Hour")),
            scripts = listOf(Script(trigger = Trigger("tick"), blocks = listOf(Block(op = "variable.set", arguments = mapOf(
                "variable" to Expression.str("hour"), "value" to Expression.input("time.hour"),
            ))))))
        return PipelineDocument(entryPoint = program.id, programs = listOf(program))
    }

    @Test fun clockControlsAffectTheSameDerivedInputsUsedByPrograms() {
        PipelineSimulation(project()).use { simulation ->
            val before = simulation.wallTimeMillis
            assertTrue(updateSimulationInput(simulation, "time.hour", number(21)))
            simulation.advanceBy(50)
            assertEquals(21.0, simulation.values.getValue("hour").number(), 0.0)
            assertEquals(21, Instant.ofEpochMilli(simulation.wallTimeMillis).atZone(ZoneId.systemDefault()).hour)
            assertNotEquals(before, simulation.wallTimeMillis)
            assertFalse(simulation.inputs.containsKey("time.hour"))
            val unchanged = simulation.wallTimeMillis
            assertFalse(updateSimulationInput(simulation, "time.hour", number(24)))
            assertFalse(updateSimulationInput(simulation, "time.minute", number(2.5)))
            assertEquals(unchanged, simulation.wallTimeMillis)
        }
    }

    @Test fun sampleValuesRetainUnitsAndRejectMalformedSensorInput() {
        val gyro = InputCatalog["sensor.gyroscope"]!!
        assertEquals(Value.Vector(1.0, 2.0, 3.0, gyro.unit), simulationValue("1,2,3", gyro))
        assertNull(simulationValue("1,NaN,3", gyro))
        assertNull(simulationValue("1,2", gyro))
        assertNull(simulationValue("maybe", InputCatalog["music.playing"]!!))
    }
}
