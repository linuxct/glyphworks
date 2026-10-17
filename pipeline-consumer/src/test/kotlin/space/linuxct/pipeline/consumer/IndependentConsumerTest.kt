package space.linuxct.pipeline.consumer

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.pipeline.*

/** No GlyphWorks, Nothing SDK, Compose or Android dependency. */
class IndependentConsumerTest {
    @Test fun `portable engine renders a custom scene using only injected ports`() {
        val script=Script(blocks=listOf(
            Block(op="scene.create"),
            Block(op="scene.rect",arguments=mapOf("x" to Expression.num(4),"y" to Expression.num(4),"width" to Expression.num(5),"height" to Expression.num(5))),
            Block(op="scene.present"),
        ))
        val program=Program(name="Independent host",scripts=listOf(script))
        val doc=PipelineDocument(entryPoint=program.id,programs=listOf(program))
        var frame=IntArray(169)
        val host=object:PipelineHost {
            override val size=13
            override val clock=object:PipelineClock{override fun elapsedMillis()=0L;override fun wallMillis()=1_790_000_000_000L}
            override val random=object:PipelineRandom{override fun nextInt(bound:Int)=0;override fun nextDouble()=0.0}
            override fun inputs()=emptyMap<String,Value>()
            override fun createNative(type:String,context:NativeContext):NativeBehavior?=null
            override fun output(frame:IntArray){thisFrame(frame)}
            private fun thisFrame(pixels:IntArray){frame=pixels}
        }
        val decoded=PipelineCodec.decode(PipelineCodec.encode(doc)) as PipelineCodec.Result.Ok
        val runtime=PipelineRuntime(decoded.document,host);runtime.start()
        assertEquals(25,frame.count{it==4095});runtime.close();assertTrue(frame.all{it==0})
    }
}
