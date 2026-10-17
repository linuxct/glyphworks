package space.linuxct.pipeline

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PipelineRuntimeTest {
    private class Host:PipelineHost {
        var elapsed=0L
        var wall=1_790_000_000_000L
        override val size=13
        override val clock=object:PipelineClock {override fun elapsedMillis()=elapsed;override fun wallMillis()=wall+elapsed}
        override val random=object:PipelineRandom {override fun nextInt(bound:Int)=bound-1;override fun nextDouble()=0.5}
        val input=mutableMapOf<String,Value>()
        val frames=mutableListOf<IntArray>()
        val errors=mutableListOf<Diagnostic>()
        val behavior=mutableListOf<Native>()
        val state=mutableMapOf<String,Value>()
        var stateWriteCount=0
        var demands=setOf<String>()
        override fun inputs()=input.toMap()
        override fun output(frame:IntArray){frames.add(frame.copyOf())}
        override fun diagnostic(diagnostic:Diagnostic){errors.add(diagnostic)}
        override fun createNative(type:String,context:NativeContext)=Native(context,type).also { behavior.add(it) }
        override fun readState(programId:String,variableId:String)=state["$programId:$variableId"]
        override fun writeState(programId:String,variableId:String,value:Value){stateWriteCount++;state["$programId:$variableId"]=value}
        override fun demand(capabilities:Set<String>){demands=capabilities}
        inner class Native(val context:NativeContext,val type:String):NativeBehavior {
            var starts=0;var paused=false;var closed=false;var events=0;var commands=0
            override fun start(){starts++;context.emitFrame(IntArray(169){if(type=="clock")1 else 2})}
            override fun suspend(){paused=true}
            override fun resume(){paused=false}
            override fun close(){closed=true}
            override fun event(event:PipelineEvent){events++}
            override fun command(name:String,arguments:Map<String,Value>){commands++}
        }
        fun step(runtime:PipelineRuntime,ms:Long){repeat((ms/10).toInt()){elapsed+=10;runtime.advance()}}
    }
    private fun block(op:String,vararg args:Pair<String,Expression>,body:List<Block> = emptyList(),otherwise:List<Block> = emptyList())=Block(op=op,arguments=mapOf(*args),body=body,otherwise=otherwise)
    private fun show(type:String="clock",slot:String="main",priority:Int=0,duration:Long=0)=block("display.toy","toy" to Expression.str(type),"slot" to Expression.str(slot),"priority" to Expression.num(priority),"duration" to Expression.ms(duration))
    private fun set(value:Int)=block("variable.set","variable" to Expression.str("score"),"value" to Expression.num(value))
    private fun doc(vararg scripts:Script,variables:List<Variable> = emptyList()):PipelineDocument {val p=Program(id="main",name="Main",variables=variables,scripts=scripts.toList());return PipelineDocument(entryPoint=p.id,programs=listOf(p))}
    private fun script(event:String="start",blocks:List<Block>,reentry:Reentry=Reentry.RESTART)=Script(trigger=Trigger(event),blocks=blocks,reentry=reentry)
    @Test fun `large selection indices cannot wrap through integer conversion`() {
        val choose=block("flow.select","index" to Expression.num(4_294_967_296L),"wrap" to Expression.bool(false),body=listOf(set(7)))
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(choose)),variables=listOf(Variable("score","Score"))),h)
        r.start();assertEquals(0.0,r.values()["score"]!!.number(),0.0);assertTrue(h.errors.toString(),h.errors.isEmpty());r.close()
    }
    @Test fun `combined presentation priority saturates without reversing its order`() {
        val high=script("shake",listOf(show("weather","high",Int.MAX_VALUE))).copy(priority=10)
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show())),high),h)
        r.start();r.dispatch(PipelineEvent("shake"));assertEquals(2,h.frames.last()[0]);assertEquals(Int.MAX_VALUE,r.presentationPriority());r.close()
    }
    @Test fun `starting holds output and identical requests preserve native instance`() {
        val d=doc(script(blocks=listOf(show())),script("tick",listOf(show())))
        val h=Host();val r=PipelineRuntime(d,h);r.start();h.step(r,1000)
        assertTrue(h.errors.toString(),h.errors.isEmpty());assertEquals(1,h.behavior.size);assertEquals(1,h.frames.last()[0]);r.close();assertTrue(h.behavior.single().closed)
    }
    @Test fun `temporary output covers and resumes background without resetting it`() {
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show())),script("shake",listOf(show("weather","overlay",10,500)))),h)
        r.start();r.dispatch(PipelineEvent("shake"));assertTrue(h.behavior[0].paused);assertEquals(2,h.frames.last()[0]);h.step(r,500)
        assertEquals(1,h.frames.last()[0]);assertFalse(h.behavior[0].paused);assertTrue(h.behavior[1].closed)
    }
    @Test fun `cancellation prevents old delayed frames replacing new presentation`() {
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show())),script("key.action",listOf(show("weather")))),h)
        r.start();val old=h.behavior.single();r.dispatch(PipelineEvent("key.action"));old.context.emitFrame(IntArray(169){4095});assertEquals(2,h.frames.last()[0]);assertTrue(old.closed)
    }
    @Test fun `blank display keeps input demands alive`() {
        val p=Program(name="Facing",scripts=listOf(Script(trigger=Trigger("tick",Expression.input("orientation.faceDown"),TriggerEdge.CHANGE,true),blocks=listOf(block("flow.if","condition" to Expression.input("orientation.faceDown"),body=listOf(show()),otherwise=listOf(block("display.off")))))))
        val h=Host();h.input["orientation.faceDown"]=boolean(true);val r=PipelineRuntime(PipelineDocument(entryPoint=p.id,programs=listOf(p)),h);r.start();assertEquals(1,h.frames.last()[0])
        h.input["orientation.faceDown"]=boolean(false);h.step(r,50);assertEquals(0,h.frames.last()[0]);assertTrue("orientation" in h.demands)
        h.input["orientation.faceDown"]=boolean(true);h.step(r,50);assertEquals(1,h.frames.last()[0])
    }
    @Test fun `event consumption prevents duplicate native action`() {
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show())),script("key.action",listOf(block("native.command","command" to Expression.str("action")),block("input.consume")))),h)
        r.start();assertTrue(r.dispatch(PipelineEvent("key.action",consumable=true)));assertEquals(1,h.behavior[0].commands);assertEquals(0,h.behavior[0].events)
    }
    @Test fun `repeated key extends timer and old timeout does not fire`() {
        val h=Host();val r=PipelineRuntime(doc(script("key.action",listOf(block("timer.start","name" to Expression.str("window"),"duration" to Expression.ms(1000)))),script("timer",listOf(set(1))),variables=listOf(Variable("score","Score"))),h)
        r.start();r.dispatch(PipelineEvent("key.action"));h.step(r,900);r.dispatch(PipelineEvent("key.action"));h.step(r,900);assertEquals(0.0,r.values()["score"]!!.number(),0.0);h.step(r,100);assertEquals(1.0,r.values()["score"]!!.number(),0.0)
    }
    @Test fun `persistent timer survives recreated engine with original deadline`() {
        val d=doc(script("key.action",listOf(block("timer.start","name" to Expression.str("deadline"),"duration" to Expression.ms(1000),"persistent" to Expression.bool(true)))),script("timer",listOf(set(9))),variables=listOf(Variable("score","Score")))
        val h=Host();val first=PipelineRuntime(d,h);first.start();first.dispatch(PipelineEvent("key.action"));h.step(first,500);first.close()
        val second=PipelineRuntime(d,h);second.start();h.step(second,500);assertEquals(9.0,second.values()["score"]!!.number(),0.0)
    }
    @Test fun `parameterized routine returns value into caller and shadows locals`() {
        val routine=Routine(id="calculate",name="Calculate",parameters=listOf(Parameter("amount","Amount")),variables=listOf(Variable("score","Local score",initial=number(2))),blocks=listOf(block("flow.return","value" to Expression.operation("add",Expression.variable("score"),Expression.parameter("amount")))))
        val d=doc(script(blocks=listOf(block("routine.call","routine" to Expression.str("calculate"),"arg:amount" to Expression.num(5),"resultVariable" to Expression.str("score")))),variables=listOf(Variable("score","Score"))).copy(routines=listOf(routine))
        val h=Host();val r=PipelineRuntime(d,h);r.start();assertTrue(h.errors.toString(),h.errors.isEmpty());assertEquals(7.0,r.values()["score"]!!.number(),0.0)
    }
    @Test fun `tight forever loop yields then stops with diagnostic`() {
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(block("flow.forever")))),h,RuntimeLimits(maxInstructionsPerTurn=20,maxStarvedTurns=3));r.start();h.step(r,100)
        assertFalse(r.isRunning);assertTrue(h.errors.any { it.message.contains("wait") });assertEquals(0,h.frames.last()[0])
    }
    @Test fun `conditional unavailable sensor never activates`() {
        val h=Host();val p=Program(name="Missing",scripts=listOf(Script(trigger=Trigger("tick",Expression.input("orientation.faceDown"),TriggerEdge.RISING,true),blocks=listOf(show()))))
        val r=PipelineRuntime(PipelineDocument(entryPoint=p.id,programs=listOf(p)),h);r.start();h.step(r,100);assertTrue(h.behavior.isEmpty())
    }
    @Test fun `stable sensor edge requires dwell before activating`() {
        val h=Host();h.input["orientation.faceDown"]=boolean(true)
        val p=Program(name="Stable",scripts=listOf(Script(trigger=Trigger("tick",Expression.input("orientation.faceDown"),TriggerEdge.RISING,true,200),blocks=listOf(show()))))
        val r=PipelineRuntime(PipelineDocument(entryPoint=p.id,programs=listOf(p)),h);r.start();h.step(r,190);assertTrue(h.behavior.isEmpty());h.step(r,10);assertEquals(1,h.behavior.size)
    }
    @Test fun `external cover freezes native without removing timers or output ownership`() {
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show()))),h);r.start();r.externallyCovered(true);assertTrue(h.behavior[0].paused);assertTrue(r.hasDisplay());r.externallyCovered(false);assertFalse(h.behavior[0].paused)
    }
    @Test fun `parallel branches join only after both waits complete`() {
        val branches=listOf(block("flow.wait","duration" to Expression.ms(100)),block("flow.wait","duration" to Expression.ms(300)))
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(block("flow.parallel",body=branches),set(4))),variables=listOf(Variable("score","Score"))),h)
        r.start();h.step(r,200);assertEquals(0.0,r.values()["score"]!!.number(),0.0);h.step(r,130);assertEquals(4.0,r.values()["score"]!!.number(),0.0)
    }
    @Test fun `portable round trip retains typed blocks and rejects recursion and unknown op`() {
        val d=doc(script(blocks=listOf(show())))
        assertEquals(d,(PipelineCodec.decode(PipelineCodec.encode(d)) as PipelineCodec.Result.Ok).document)
        assertTrue(PipelineCodec.decode(PipelineCodec.encode(doc(script(blocks=listOf(block("evil")))))) is PipelineCodec.Result.Invalid)
        val r=Routine(id="loop",name="Recursive",blocks=listOf(block("routine.call","routine" to Expression.str("loop"))))
        assertTrue(PipelineCodec.validate(d.copy(routines=listOf(r))).any { it.message.contains("cycle") })
    }
    @Test fun `draft allows unfinished references but applied document does not`() {
        val d=doc(script(blocks=listOf(block("display.animation"))))
        val encoded=PipelineCodec.encode(d)
        assertTrue(PipelineCodec.decodeDraft(encoded) is PipelineCodec.Result.Ok);assertTrue(PipelineCodec.decode(encoded) is PipelineCodec.Result.Invalid)
    }
    @Test fun `oversized and deeply nested input is rejected before parsing`() {
        assertTrue(PipelineCodec.decode("[".repeat(113)+"]".repeat(113)) is PipelineCodec.Result.Invalid)
        assertTrue(PipelineCodec.decode(" ".repeat(PipelineCodec.MAX_BYTES+1)) is PipelineCodec.Result.Invalid)
    }
    @Test fun `native scheduler callbacks use VM queue and cannot fire after replacement`() {
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show())),script("signal.switch",listOf(show("weather")))),h)
        r.start();val first=h.behavior.single();var callbacks=0
        first.context.scheduler.post(50){callbacks++}
        h.step(r,50);assertEquals(1,callbacks)
        first.context.scheduler.post(50){callbacks++}
        r.dispatch(PipelineEvent("signal.switch"));h.step(r,100);assertEquals(1,callbacks)
    }
    @Test fun `overlay deadline cancels a forever body and resumes underlying display`() {
        val overlay=block("display.overlay","duration" to Expression.ms(100),body=listOf(show("weather"),block("flow.forever",body=listOf(block("flow.wait","duration" to Expression.ms(1000))))))
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show())),script("shake",listOf(overlay))),h)
        r.start();r.dispatch(PipelineEvent("shake"));h.step(r,20);assertEquals(2,h.frames.last()[0]);h.step(r,100);assertEquals(1,h.frames.last()[0]);assertTrue(h.behavior.last().closed)
    }
    @Test fun `not unavailable does not turn missing sensor into true condition`() {
        val condition=Expression.operation("not",Expression.input("orientation.faceDown"))
        val p=Program(name="Missing sensor",scripts=listOf(Script(trigger=Trigger("tick",condition,TriggerEdge.RISING,true),blocks=listOf(show()))))
        val h=Host();val r=PipelineRuntime(PipelineDocument(entryPoint=p.id,programs=listOf(p)),h);r.start();h.step(r,100);assertTrue(h.behavior.isEmpty())
    }

    @Test fun `visible wait ignores time covered by another lease or host preview`() {
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show(),block("flow.waitVisible","duration" to Expression.ms(200)),set(7))),script("shake",listOf(show("weather","cover",10,100))),variables=listOf(Variable("score","Score"))),h)
        r.start();h.step(r,50);r.dispatch(PipelineEvent("shake"));h.step(r,100);assertEquals(0.0,r.values()["score"]!!.number(),0.0)
        r.externallyCovered(true);h.step(r,500);r.externallyCovered(false);h.step(r,140);assertEquals(0.0,r.values()["score"]!!.number(),0.0)
        h.step(r,20);assertEquals(7.0,r.values()["score"]!!.number(),0.0)
    }
    @Test fun `unavailable increments remain unavailable and dynamic records evaluate fields`() {
        val change=block("variable.change","variable" to Expression.str("score"),"by" to Expression.input("speed.bytesPerSecond"))
        val setRecord=block("variable.set","variable" to Expression.str("data"),"value" to Expression.operation("record",Expression.str("score"),Expression.variable("score"),Expression.str("ok"),Expression.bool(true)))
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(change,setRecord)),variables=listOf(Variable("score","Score"),Variable("data","Data",ValueType.RECORD,Value.Record()))),h)
        r.start();assertTrue(h.errors.toString(),h.errors.isEmpty());assertTrue(r.values()["score"] is Value.Unavailable)
        assertEquals(boolean(true),(r.values()["data"] as Value.Record).fields["ok"])
    }
    @Test fun `two instances of same child keep persistent variables independent across restart`() {
        val child=Program(id="child",name="Child",parameters=listOf(Parameter("amount","Amount")),variables=listOf(Variable("count","Count",persistent=true)),scripts=listOf(script(blocks=listOf(block("variable.change","variable" to Expression.str("count"),"by" to Expression.parameter("amount"))))))
        val a=block("program.run","program" to Expression.str("child"),"slot" to Expression.str("left"),"arg:amount" to Expression.num(1))
        val b=block("program.run","program" to Expression.str("child"),"slot" to Expression.str("right"),"arg:amount" to Expression.num(5))
        val parent=Program(id="main",name="Main",scripts=listOf(script(blocks=listOf(a,b))))
        val d=PipelineDocument(entryPoint="main",programs=listOf(parent,child));val h=Host()
        val first=PipelineRuntime(d,h);first.start();h.step(first,20);first.close();val second=PipelineRuntime(d,h);second.start();h.step(second,20)
        assertTrue(h.errors.toString(),h.errors.isEmpty());assertEquals(number(2),h.state["child:instance:root/left:count"]);assertEquals(number(10),h.state["child:instance:root/right:count"])
    }
    @Test fun `stop handler saves state before cancellation and cannot recurse forever`() {
        val stop=script("stop",listOf(set(42),block("flow.stop","scope" to Expression.str("program"))))
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(show())),stop,variables=listOf(Variable("score","Score",persistent=true))),h)
        r.start();r.close();assertEquals(number(42),h.state["main:score"]);assertTrue(h.behavior.single().closed);assertFalse(r.isRunning)
    }
    @Test fun `daily schedule is once per local date through DST and recreation with no backlog`() {
        val originalZone=java.util.TimeZone.getDefault();java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Madrid"))
        try {
            val schedule=Script(trigger=Trigger("calendar.daily",calendar=CalendarTrigger(2,30)),blocks=listOf(block("variable.change","variable" to Expression.str("score"),"by" to Expression.num(1))))
            val d=doc(schedule,variables=listOf(Variable("score","Score",persistent=true)));val h=Host()
            h.wall=java.time.Instant.parse("2026-10-25T00:30:00Z").toEpochMilli();var r=PipelineRuntime(d,h);r.start();assertEquals(number(1),r.values()["score"])
            r.close();h.wall=java.time.Instant.parse("2026-10-25T01:30:00Z").toEpochMilli();r=PipelineRuntime(d,h);r.start();assertEquals(number(1),r.values()["score"])
            h.wall=java.time.Instant.parse("2026-10-26T03:00:00Z").toEpochMilli();r.advance();assertEquals(number(1),r.values()["score"])
            h.wall=java.time.Instant.parse("2026-10-27T01:30:00Z").toEpochMilli();r.advance();assertEquals(number(2),r.values()["score"])
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    private fun animatedDesign():JsonObject = buildJsonObject {
        put("format","glyph.design");put("formatVersion",1);put("id","art");put("name","Two frames");put("kind","dynamic")
        putJsonArray("levels"){add(0);add(2048);add(4095)}
        putJsonObject("variants") {
            for((size,key) in listOf(13 to "bellsprout",25 to "arbok"))putJsonObject(key){
                put("size",size);putJsonArray("frames") {
                    for(level in listOf('1','2'))add(buildJsonObject {put("durationMs",100);put("cells",level.toString().repeat(size*size))})
                }
            }
        }
    }
    @Test fun `animation pauses behind overlays and supports seek and pause controls`() {
        fun control(command:String,time:Long=0)=block("display.control","command" to Expression.str(command),"position" to Expression.ms(time))
        val d=doc(script(blocks=listOf(block("display.animation","asset" to Expression.str("art")))),script("shake",listOf(show("weather","cover",10,100))),script("signal.pause",listOf(control("pause"))),script("signal.seek",listOf(control("seek",100))),script("signal.resume",listOf(control("resume")))).copy(designs=mapOf("art" to animatedDesign()))
        val h=Host();val r=PipelineRuntime(d,h);r.start();assertTrue(h.errors.toString(),h.errors.isEmpty());assertEquals(2048,h.frames.last()[0]);h.step(r,50)
        r.dispatch(PipelineEvent("shake"));h.step(r,100);assertEquals(2048,h.frames.last()[0]);h.step(r,50);assertEquals(4095,h.frames.last()[0])
        r.dispatch(PipelineEvent("signal.pause"));h.step(r,150);assertEquals(4095,h.frames.last()[0]);r.dispatch(PipelineEvent("signal.seek"));assertEquals(4095,h.frames.last()[0])
        r.dispatch(PipelineEvent("signal.resume"));h.step(r,100);assertEquals(2048,h.frames.last()[0])
    }
    @Test fun `clip applies to drawing and design composition on both panel sizes`() {
        val d=doc(script(blocks=emptyList())).copy(designs=mapOf("art" to animatedDesign()))
        for(size in listOf(13,25)) {
            val scene=Scene(size,PipelineAssets(d),RuntimeLimits());scene.clip(true,2,2,3,3);scene.design("art",0,0,0,false)
            val frame=scene.render(0);assertEquals(9,frame.count {it==2048});assertEquals(0,frame[1*size+2])
            scene.clear();scene.clip(false,0,0,0,0);scene.circle(size/2,size/2,2,4095,false)
            val circle=scene.render(0);assertEquals(4095,circle[(size/2)*size+size/2+2]);assertEquals(0,circle[(size/2)*size+size/2])
        }
    }

    @Test fun `inactive program references and disabled scripts do not subscribe to sensors`() {
        val child=Program(id="child",name="Weather",scripts=listOf(script(blocks=listOf(show("weather")))))
        val parent=Program(id="main",name="Menu",scripts=listOf(script(blocks=listOf(show())),script("shake",listOf(block("program.run","program" to Expression.str("child")))),script("key.action",listOf(block("program.stop"))),Script(enabled=false,trigger=Trigger("tick",Expression.input("orientation.faceDown")))))
        val h=Host();val r=PipelineRuntime(PipelineDocument(entryPoint="main",programs=listOf(parent,child)),h);r.start()
        assertEquals(setOf("accelerometer"),h.demands)
        r.dispatch(PipelineEvent("shake"));h.step(r,20);assertEquals(setOf("accelerometer","weather"),h.demands)
        r.dispatch(PipelineEvent("key.action"));assertEquals(setOf("accelerometer"),h.demands)
        r.close();assertTrue(h.demands.isEmpty())
    }

    @Test fun `controller consumption prevents focused child script and native from receiving key`() {
        val child=Program(id="child",name="Game",variables=listOf(Variable("count","Count",persistent=true)),scripts=listOf(script(blocks=listOf(show())),script("key.action",listOf(block("variable.change","variable" to Expression.str("count"),"by" to Expression.num(1))))))
        val root=Program(id="main",name="Menu",scripts=listOf(script(blocks=listOf(block("program.run","program" to Expression.str("child")))),script("key.action",listOf(block("input.consume")))))
        val h=Host();val r=PipelineRuntime(PipelineDocument(entryPoint="main",programs=listOf(root,child)),h);r.start();h.step(r,20)
        assertTrue(r.dispatch(PipelineEvent("key.action",consumable=true)))
        assertNull(h.state["child:instance:root/child:count"]);assertEquals(0,h.behavior.single().events)
    }

    @Test fun `duration calculations retain units and numeric comparisons accept literal thresholds`() {
        val scaled=Expression.operation("multiply",Expression.num(2),Expression.ms(1500))
        val clamped=Expression.operation("clamp",scaled,Expression.ms(1000),Expression.ms(2500))
        val rounded=Expression.operation("round",Expression.operation("divide",clamped,Expression.num(2)))
        val program=Program(name="Units",variables=listOf(Variable("wait","Wait",ValueType.DURATION,duration(0)),Variable("full","Full",ValueType.BOOLEAN,boolean(false))),scripts=listOf(script(blocks=listOf(
            block("variable.set","variable" to Expression.str("wait"),"value" to rounded),
            block("variable.set","variable" to Expression.str("full"),"value" to Expression.operation("equal",Expression.input("battery.level"),Expression.num(100)))
        ))))
        val h=Host();h.input["battery.level"]=Value.Number(100.0,UnitKind.PERCENT);val r=PipelineRuntime(PipelineDocument(entryPoint=program.id,programs=listOf(program)),h);r.start()
        assertTrue(h.errors.toString(),h.errors.isEmpty());assertEquals(duration(1250),r.values()["wait"]);assertEquals(boolean(true),r.values()["full"])
    }

    @Test fun `persistent timer in a routine restores only while that authored timer remains`() {
        val timer=block("timer.start","name" to Expression.str("wake"),"duration" to Expression.ms(500),"persistent" to Expression.bool(true))
        val routine=Routine(id="arm",name="Arm",blocks=listOf(timer))
        val d=doc(script("key.action",listOf(block("routine.call","routine" to Expression.str("arm")))),script("timer",listOf(set(8))),variables=listOf(Variable("score","Score"))).copy(routines=listOf(routine))
        val h=Host();var r=PipelineRuntime(d,h);r.start();r.dispatch(PipelineEvent("key.action"));h.step(r,200);r.close()
        r=PipelineRuntime(d,h);r.start();h.step(r,300);assertEquals(number(8),r.values()["score"]);r.close()
        r=PipelineRuntime(d,h);r.start();r.dispatch(PipelineEvent("key.action"));r.close()
        val changed=d.copy(routines=listOf(routine.copy(blocks=emptyList())))
        r=PipelineRuntime(changed,h);r.start();h.step(r,600);assertEquals(number(0),r.values()["score"])
    }

    @Test fun `persistent writes coalesce within an interpreter turn`() {
        val change=block("variable.change","variable" to Expression.str("score"),"by" to Expression.num(1))
        val h=Host();val r=PipelineRuntime(doc(script(blocks=listOf(block("flow.repeat","count" to Expression.num(100),body=listOf(change)))),variables=listOf(Variable("score","Score",persistent=true))),h)
        r.start();assertEquals(number(100),h.state["main:score"]);assertEquals(1,h.stateWriteCount)
    }
    @Test fun `recursive record growth is stopped before serialization or unbounded memory`() {
        val nest=block("variable.set","variable" to Expression.str("tree"),"value" to Expression.operation("record",Expression.str("child"),Expression.variable("tree")))
        val p=Program(name="Bounded",variables=listOf(Variable("tree","Tree",ValueType.RECORD,Value.Record(),true)),scripts=listOf(script(blocks=listOf(block("flow.repeat","count" to Expression.num(100),body=listOf(nest))))))
        val h=Host();val r=PipelineRuntime(PipelineDocument(entryPoint=p.id,programs=listOf(p)),h);r.start();assertFalse(r.isRunning);assertTrue(h.errors.any {it.message.contains("budget")})
    }

}
