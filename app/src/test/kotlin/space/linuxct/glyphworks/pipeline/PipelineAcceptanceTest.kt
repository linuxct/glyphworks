package space.linuxct.glyphworks.pipeline

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.glyphworks.pipeline.templates.*
import space.linuxct.pipeline.*

class PipelineAcceptanceTest {
    @Test fun `every immutable template and starter validates and runs on both panels`() {
        for(doc in BuiltinPipelines.all()+BuiltinPipelines.examples()) {
            assertEquals("${doc.name}: ${PipelineCodec.validate(doc)}",emptyList<Diagnostic>(),PipelineCodec.validate(doc))
            for(size in listOf(13,25))PipelineSimulation(doc,size).use { sim->
                sim.advanceBy(1200)
                assertTrue("${doc.name}/$size: ${sim.diagnostics}",sim.diagnostics.isEmpty())
                assertTrue("${doc.name} prematurely stopped",sim.running)
                assertEquals(size*size,sim.frame.size)
                assertTrue(sim.frame.all{it in 0..4095})
            }
        }
    }
    @Test fun `generic runner collides shows score and restarts without native Dino`() {
        val doc=AuthoredRunner.create()
        fun native(bs:List<Block>):Boolean=bs.any{it.op=="display.toy"||native(it.body)||native(it.otherwise)}
        assertFalse(doc.programs.any{p->p.scripts.any{native(it.blocks)}}||doc.routines.any{native(it.blocks)})
        PipelineSimulation(doc).use {sim->
            assertTrue(sim.diagnostics.toString(),sim.diagnostics.isEmpty())
            assertTrue(sim.values["running"]!!.boolean())
            val standing=sim.frame.copyOf();sim.dispatch(PipelineEvent("key.action"));sim.advanceBy(200)
            assertFalse(standing.contentEquals(sim.frame))
            sim.advanceBy(12_000)
            assertFalse(sim.values["running"]!!.boolean())
            sim.dispatch(PipelineEvent("key.action"));assertTrue(sim.values["running"]!!.boolean())
            assertEquals(0.0,sim.values["score"]!!.number(),0.0)
        }
    }
    @Test fun `custom authored menu opens advances confirms and returns home`() {
        PipelineSimulation(ControllerTemplate.create()).use {sim->
            assertTrue(sim.diagnostics.toString(),sim.diagnostics.isEmpty())
            assertFalse(sim.values["menu"]!!.boolean())
            sim.dispatch(PipelineEvent("key.double"));assertTrue(sim.values["menu"]!!.boolean())
            sim.dispatch(PipelineEvent("key.action"));assertEquals(1.0,sim.values["selection"]!!.number(),0.0)
            sim.advanceBy(5100);assertFalse(sim.values["menu"]!!.boolean())
            sim.dispatch(PipelineEvent("key.triple"));assertEquals(0.0,sim.values["selection"]!!.number(),0.0)
            assertTrue(sim.diagnostics.toString(),sim.diagnostics.isEmpty())
        }
    }
    @Test fun `Ambient music override returns to background and retrigger extends deadline`() {
        val original=BuiltinPipelines.ambient()
        val doc=original.copy(programs=original.programs.map{it.copy(values=it.values+("overrideDuration" to duration(1000)))})
        PipelineSimulation(doc).use {sim->
            assertTrue(sim.diagnostics.toString(),sim.diagnostics.isEmpty())
            sim.inputs["music.energy"]=number(0.5);sim.inputs["music.playing"]=boolean(true);sim.advanceBy(100)
            val music=sim.frame.copyOf()
            sim.dispatch(PipelineEvent("key.action"));sim.advanceBy(100);assertFalse(music.contentEquals(sim.frame))
            val background=sim.frame.copyOf();sim.advanceBy(700);sim.dispatch(PipelineEvent("key.action"));sim.advanceBy(800)
            assertTrue(background.contentEquals(sim.frame));sim.advanceBy(400);assertFalse(background.contentEquals(sim.frame))
            assertTrue(sim.diagnostics.toString(),sim.diagnostics.isEmpty())
        }
    }
}
