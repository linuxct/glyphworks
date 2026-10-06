package space.linuxct.glyphworks.pipeline

import space.linuxct.glyphworks.core.*
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.weather.*
import space.linuxct.glyphworks.pipeline.native.NativeLibrary
import space.linuxct.pipeline.*
import java.time.Instant
import java.time.ZoneId

/** Production interpreter + native library with entirely local, user-controlled inputs. */
class PipelineSimulation(val document:PipelineDocument,val size:Int=13):AutoCloseable {
    var frame=IntArray(size*size); private set
    val diagnostics=mutableListOf<Diagnostic>()
    val inputs=mutableMapOf<String,Value>(
        "sdk.available" to boolean(true),"session.running" to boolean(true),"orientation.edge" to boolean(false),
        "battery.plugged" to boolean(false),"battery.full" to boolean(false),
        "battery.level" to Value.Number(64.0,UnitKind.PERCENT),"battery.charging" to boolean(false),"battery.watts" to number(18),
        "music.playing" to boolean(false),"music.energy" to number(0),"notifications.count" to number(3),
        "orientation.faceDown" to boolean(true),"orientation.faceUp" to boolean(false),"orientation.pitch" to Value.Number(0.0,UnitKind.DEGREES),"orientation.roll" to Value.Number(0.0,UnitKind.DEGREES),
        "sensor.acceleration" to Value.Vector(0.0,0.0,-9.81,UnitKind.METERS_PER_SECOND_SQUARED),"sensor.gravity" to Value.Vector(0.0,0.0,-9.81,UnitKind.METERS_PER_SECOND_SQUARED),"sensor.gyroscope" to Value.Vector(0.0,0.0,0.0,UnitKind.RADIANS_PER_SECOND),
        "sensor.light" to Value.Number(120.0,UnitKind.LUX),"sensor.proximity" to boolean(false),"sensor.heading" to Value.Number(35.0,UnitKind.DEGREES),"screen.on" to boolean(false),"device.locked" to boolean(true),
        "connection.state" to text("wifi"),"speed.bytesPerSecond" to number(512_000),"weather.temperature" to number(18),"weather.condition" to text("clear"),"weather.day" to boolean(true),
        "location.latitude" to number(40.4),"location.longitude" to number(-3.7),
    )
    private var elapsed=0L
    private var epoch=Instant.parse("2026-10-06T13:50:00Z").toEpochMilli()
    private val random=java.util.Random(73L)
    private val memory=mutableMapOf<String,Value>()
    val actions=mutableListOf<Pair<String,Map<String,Value>>>()
    private val preferences=SimulationPreferences()
    private val clock=object:PipelineClock {override fun elapsedMillis()=elapsed;override fun wallMillis()=epoch+elapsed}
    private val ports=Ports(
        clock=object:ClockPort {
            override fun nowMillis()=clock.wallMillis();override fun elapsedMillis()=elapsed
            private fun date()=Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault())
            override fun hourOfDay()=date().hour;override fun minute()=date().minute;override fun second()=date().second;override fun utcOffsetMinutes()=date().offset.totalSeconds/60;override fun dayOfYear()=date().dayOfYear
        },
        random=object:RandomPort {override fun nextInt(bound:Int)=random.nextInt(bound);override fun nextFloat()=random.nextFloat()},
        battery=object:BatteryPort {override fun levelPercent()=inputs["battery.level"]!!.number().toInt();override fun isCharging()=inputs["battery.charging"]!!.boolean();override fun chargeWatts()=inputs["battery.watts"]?.numberOrNull()?.toFloat()},
        speed=object:SpeedPort {override fun totalRxBytes()=(elapsed/1000.0*(inputs["speed.bytesPerSecond"]?.number()?:0.0)).toLong()},
        spectrum=object:SpectrumPort {override fun bands(n:Int):FloatArray? {
            if(inputs["music.playing"]?.boolean()!=true)return null
            val supplied=(inputs["music.bands"] as? Value.Items)?.values
            return FloatArray(n){i-> supplied?.getOrNull(i)?.number()?.toFloat()?:((kotlin.math.sin(elapsed/170.0+i*0.9)+1.0)*0.35+0.1).toFloat()}
        }},
        azimuth=object:AzimuthPort {override fun azimuthDegrees()=inputs["sensor.heading"]?.numberOrNull()?.toFloat()},
        shake=object:ShakePort {override fun millisSinceLastShake()=elapsed-lastShake},
        tilt=object:TiltPort {override fun tiltX()=inputs["orientation.roll"]?.number()?.toFloat()?:0f;override fun tiltY()=inputs["orientation.pitch"]?.number()?.toFloat()?:0f},
        incline=object:InclinePort {override fun pitchDegrees()=inputs["orientation.pitch"]?.numberOrNull()?.toFloat();override fun rollDegrees()=inputs["orientation.roll"]?.numberOrNull()?.toFloat()},
        light=object:LightPort {override fun lux()=inputs["sensor.light"]?.numberOrNull()?.toFloat()},
        connectivity=object:ConnectivityPort {override fun state()=runCatching{ConnectionState.valueOf(inputs["connection.state"]!!.text().uppercase())}.getOrDefault(ConnectionState.NONE)},
        location=object:LocationPort {override fun latLon()=inputs["location.latitude"]?.numberOrNull()?.let {lat->inputs["location.longitude"]?.numberOrNull()?.let{lon->lat to lon}}},
        timer=object:TimerSignalPort {override fun scheduleAlarm(atEpochMillis:Long)=Unit;override fun cancelAlarm()=Unit;override fun chime()=Unit},
        design=object:DesignPort {override fun selected():Design?=null},
        notifications=NotificationPort {inputs["notifications.count"]?.numberOrNull()?.toInt()},
        weather=object:WeatherPort {
            override fun snapshot()=WeatherSnapshot(if(inputs["weather.temperature"] is Value.Unavailable)WeatherStatus.UNAVAILABLE else WeatherStatus.READY,
                inputs["weather.temperature"]?.numberOrNull(),runCatching{WeatherCondition.valueOf(inputs["weather.condition"]!!.text().uppercase())}.getOrDefault(WeatherCondition.CLEAR),inputs["weather.day"]?.boolean()?:true,epoch)
            override fun setActive(active:Boolean)=Unit
        },
    )
    private var lastShake=-60_000L
    private val runtime=PipelineRuntime(document,object:PipelineHost {
        override val clock=this@PipelineSimulation.clock
        override val random=object:PipelineRandom {override fun nextInt(bound:Int)=this@PipelineSimulation.random.nextInt(bound);override fun nextDouble()=this@PipelineSimulation.random.nextDouble()}
        override val size=this@PipelineSimulation.size
        override fun inputs()=this@PipelineSimulation.inputs.toMap()
        override fun createNative(type:String,context:NativeContext)=NativeLibrary.create(type,context,ports,preferences)
        override fun output(frame:IntArray){this@PipelineSimulation.frame=frame.copyOf()}
        override fun diagnostic(diagnostic:Diagnostic){diagnostics.add(diagnostic)}
        override fun readState(programId:String,variableId:String)=memory["$programId:$variableId"]
        override fun writeState(programId:String,variableId:String,value:Value){memory["$programId:$variableId"]=value}
        override fun action(name:String,values:Map<String,Value>){actions.add(name to values);if(actions.size>128)actions.removeAt(0)}
    })
    val trace:List<TraceEntry> get()=runtime.trace()
    val values:Map<String,Value> get()=runtime.values()
    val activeBlockIds:Set<String> get()=runtime.debugSnapshot().activeBlocks
    val waitingBlockIds:Set<String> get()=runtime.debugSnapshot().waitingBlocks
    val running get()=runtime.isRunning
    val elapsedMillis get()=elapsed
    val wallTimeMillis get()=clock.wallMillis()
    fun setWallTime(epochMillis:Long) {epoch=epochMillis-elapsed;runtime.advance()}
    init {runtime.start()}
    fun advanceBy(ms:Long) {
        var remaining=ms.coerceIn(0,86_400_000)
        while(remaining>0&&runtime.isRunning) {val step=minOf(20L,remaining);elapsed+=step;remaining-=step;runtime.advance()}
        if(ms==0L)runtime.advance()
    }
    fun dispatch(event:PipelineEvent) {if(event.name=="shake")lastShake=elapsed;runtime.dispatch(event.copy(atMillis=elapsed))}
    override fun close()=runtime.close()
}

private class SimulationPreferences:Prefs {
    private val values=mutableMapOf<String,Any>()
    override fun getBoolean(key:String,def:Boolean)=values[key] as? Boolean?:def
    override fun getInt(key:String,def:Int)=(values[key] as? Number)?.toInt()?:def
    override fun getLong(key:String,def:Long)=(values[key] as? Number)?.toLong()?:def
    override fun getFloat(key:String,def:Float)=(values[key] as? Number)?.toFloat()?:def
    override fun getString(key:String,def:String)=values[key] as? String?:def
    override fun contains(key:String)=values.containsKey(key)
    override fun remove(key:String){values.remove(key)}
    override fun putBoolean(key:String,v:Boolean){values[key]=v};override fun putInt(key:String,v:Int){values[key]=v};override fun putLong(key:String,v:Long){values[key]=v};override fun putFloat(key:String,v:Float){values[key]=v};override fun putString(key:String,v:String){values[key]=v}
    override fun addChangeListener(listener:(String)->Unit)=Unit;override fun removeChangeListener(listener:(String)->Unit)=Unit
}
