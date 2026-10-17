package space.linuxct.pipeline.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import space.linuxct.pipeline.*

/** Android scheduling adapter only; the host owns permissions, sensors and display access. */
class AndroidPipelineSession(
    document:PipelineDocument,
    host:PipelineHost,
    private val handler:Handler,
    private val frameIntervalMs:Long=20,
    limits:RuntimeLimits=RuntimeLimits(),
    preparedAssets:PipelineAssets?=null,
):AutoCloseable {
    private val runtime=PipelineRuntime(document,host,limits,preparedAssets)
    @Volatile private var closed=false
    private var started=false
    private val tick=object:Runnable {override fun run(){
        if(closed)return
        runtime.advance()
        if(runtime.isRunning)handler.postDelayed(this,frameIntervalMs.coerceIn(10,1000))
    }}
    fun start()=onOwner {if(!started&&!closed){started=true;runtime.start();handler.post(tick)}}
    fun dispatch(event:PipelineEvent)=onOwner {if(!closed)runtime.dispatch(event)}
    fun inspect(callback:(List<TraceEntry>,Map<String,Value>)->Unit)=onOwner {callback(runtime.trace(),runtime.values())}
    fun covered(value:Boolean)=onOwner {if(!closed)runtime.externallyCovered(value)}
    override fun close() {closed=true;onOwner {handler.removeCallbacks(tick);runtime.close()}}
    private fun onOwner(action:()->Unit) {if(Looper.myLooper()==handler.looper)action()else handler.post {action()}}
}

object AndroidPipelineClock:PipelineClock {
    override fun elapsedMillis()=SystemClock.elapsedRealtime()
    override fun wallMillis()=System.currentTimeMillis()
}
