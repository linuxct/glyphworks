package space.linuxct.pipeline.consumer

import android.app.Application
import android.os.Handler
import android.os.Looper
import space.linuxct.pipeline.*
import space.linuxct.pipeline.android.*

/** Build/R8 fixture: consumes the Android SDK without GlyphWorks, Compose or Nothing SDK. */
class ConsumerApplication:Application() {
    private var session:AndroidPipelineSession?=null
    private var latestFrame=IntArray(169)
    override fun onCreate() {
        super.onCreate()
        val program=Program(name="Fixture",scripts=listOf(Script(blocks=listOf(Block(op="scene.create"),Block(op="scene.pixel",arguments=mapOf("x" to Expression.num(6),"y" to Expression.num(6))),Block(op="scene.present")))))
        val document=PipelineDocument(entryPoint=program.id,programs=listOf(program))
        val decoded=PipelineCodec.decode(PipelineCodec.encode(document)) as PipelineCodec.Result.Ok
        val host=object:PipelineHost {
            override val size=13
            override val clock=AndroidPipelineClock
            override val random=object:PipelineRandom {
                private val source=java.util.Random(1)
                override fun nextInt(bound:Int)=source.nextInt(bound)
                override fun nextDouble()=source.nextDouble()
            }
            override fun inputs()=emptyMap<String,Value>()
            override fun createNative(type:String,context:NativeContext):NativeBehavior?=null
            override fun output(frame:IntArray){latestFrame=frame.copyOf()}
        }
        val handler=Handler(Looper.getMainLooper())
        session=AndroidPipelineSession(decoded.document,host,handler).also{it.start()}
        handler.postDelayed({session?.close();session=null},1000)
    }
}
