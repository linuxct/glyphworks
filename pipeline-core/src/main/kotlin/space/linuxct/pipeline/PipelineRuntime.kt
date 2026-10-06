package space.linuxct.pipeline

import java.util.PriorityQueue
import java.util.Locale
import kotlin.math.*

/**
 * Single-owner deterministic VM. Hosts call all methods on one serialized executor, including
 * [advance]. No thread, Android API, wall-clock sleep or hardware lease is created by the VM.
 * A held presentation survives its event handler; an overlay and its callbacks are scoped.
 */
class PipelineRuntime(val document:PipelineDocument, private val host:PipelineHost, private val limits:RuntimeLimits=RuntimeLimits(), preparedAssets:PipelineAssets?=null):AutoCloseable {
    private data class Task(val due:Long,val order:Long,val action:()->Unit,var cancelled:Boolean=false)
    private val tasks=PriorityQueue<Task>(compareBy<Task>{it.due}.thenBy{it.order})
    private var sequence=0L
    private var closed=false
    private var started=false
    private var pumping=false
    private var lastTick=Long.MIN_VALUE
    private val events=ArrayDeque<PipelineEvent>()
    private val traceLog=ArrayDeque<TraceEntry>()
    private val instances=linkedMapOf<String,Instance>()
    private val fibers=linkedMapOf<Long,Fiber>()
    private val leases=linkedMapOf<String,Lease>()
    private var winner:String?=null
    private var lastOutput:IntArray?=null
    private val authoredBlocks by lazy {buildMap<String,Block> {
        fun visit(blocks:List<Block>) {blocks.forEach {put(it.id,it);visit(it.body);visit(it.otherwise)}}
        document.programs.forEach {it.scripts.forEach {visit(it.blocks)}};document.routines.forEach {visit(it.blocks)}
    }}
    private val assets by lazy { preparedAssets?:PipelineAssets(document) }
    private var demanded=emptySet<String>()
    private val stateWrites=linkedMapOf<Pair<String,String>,Value>()
    private val baseInputs=linkedMapOf<String,Value>()
    private var faulted=false
    private var covered=false
    private var closing=false
    private val stoppingInstances=mutableSetOf<String>()
    private val hostActionTimes=mutableMapOf<String,Long>()
    private inner class Instance(val id:String,val program:Program,val parent:String?=null,val arguments:Map<String,Value> = emptyMap(),val presentationScope:String?=null,val basePriority:Int=0) {
        val variables=program.variables.associate { it.id to (if(it.persistent) readState(program.id,storageKey(id,it.id))?.takeIf { v->v.matches(it.type) }?:it.initial else it.initial) }.toMutableMap()
        val parameters=program.parameters.associate { it.id to (arguments[it.id]?:program.values[it.id]?:it.default) }
        val timers=mutableMapOf<String,Timer>()
        val scriptFibers=mutableMapOf<String,MutableSet<Long>>()
        val queues=mutableMapOf<String,ArrayDeque<PipelineEvent>>()
        val edges=mutableMapOf<String,EdgeState>()
        val calendarDates=mutableMapOf<String,Long>()
        val calendarDeadlines=mutableMapOf<String,Long>()
        val children=mutableMapOf<String,String>()
        val scene=Scene(host.size,assets,limits)
        var drawings:DrawingRoutineRenderer?=null
        fun draw(routine:String,state:Map<String,Value>):IntArray? {
            val renderer=drawings?:DrawingRoutineRenderer(document,program,host,{variables.toMap()},parameters,assets,limits).also {drawings=it}
            return renderer.render(routine,state)
        }
    }
    private data class EdgeState(var current:Boolean=false,var candidate:Boolean=false,var since:Long=0,var initialized:Boolean=false)
    private data class Timer(val deadline:Long,val wallDeadline:Long,val persistent:Boolean,val generation:Long,val blockId:String?=null)
    private data class Cursor(val blocks:List<Block>,var index:Int=0,val repeat:Block?=null,var remaining:Int=0,val routine:Routine?=null,
                              val locals:MutableMap<String,Value>?=null,val parameters:Map<String,Value>?=null,val resultVariable:String?=null)
    private inner class Fiber(val id:Long,val instance:Instance,val script:Script,var event:PipelineEvent,val branch:List<Block>,val parent:Fiber?=null) {
        val stack=ArrayDeque<Cursor>().apply { addLast(Cursor(branch)) }
        var waitingUntil=0L
        var visibleWaitSlot:String?=null
        var visibleRemaining=0L
        var visibleCheckedAt=0L
        var waitingCondition:Expression?=null
        var waitingEvent:String?=null
        var eventValues=event.values
        var consumed=false
        var stopped=false
        var starvation=0
        var pendingChildren=mutableSetOf<Long>()
        var overlayPrefix:String=parent?.overlayPrefix.orEmpty()
        var overlayPriority:Int=parent?.overlayPriority?:instance.basePriority
        var overlayScope:String?=parent?.overlayScope?:instance.presentationScope
        var ownsOverlay=false
        var overlayEnds:Long?=parent?.overlayEnds
        var resumeCleanup:(()->Unit)?=null
    }
    private inner class Lease(val key:String,val owner:String,val slot:String,val signature:String,val priority:Int,val order:Long,val scope:String?,val frameSource:((Long)->IntArray?)?=null) {
        var frame=IntArray(host.size*host.size)
        var behavior:NativeBehavior?=null
        var capabilities:Set<String> = emptySet()
        var active=true
        var visible=true
        var playhead=0L
        var sampledAt=now()
        var animationPaused=false
        val cancellations=mutableListOf<Cancellation>()
        fun close() { if(!active)return;active=false; cancellations.toList().forEach { it.cancel() };cancellations.clear();behavior?.close() }
    }
    private fun now()=host.clock.elapsedMillis()
    private fun storagePrefix(instance:String)=if(instance=="root")"" else "instance:$instance:"
    private fun storageKey(instance:String,key:String)=storagePrefix(instance)+key
    val isRunning get()=started&&!closed&&!faulted
    fun hasDisplay():Boolean=leases.values.any { it.active }
    fun presentationPriority():Int?=leases.values.filter { it.active }.maxOfOrNull { it.priority }
    /** Keeps rules/timers alive while another program temporarily owns the physical panel. */
    fun externallyCovered(value:Boolean) { if(covered==value)return;accountVisibility();covered=value;compose();if(isRunning)dispatch(PipelineEvent(if(value)"visibility.hidden" else "visibility.shown",atMillis=now())) }
    fun trace():List<TraceEntry> = traceLog.toList()
    fun values():Map<String,Value> = instances.values.firstOrNull()?.variables?.toMap().orEmpty()
    fun debugSnapshot():RuntimeSnapshot {
        val active=mutableSetOf<String>();val waiting=mutableSetOf<String>()
        for(f in fibers.values) {
            val cursor=f.stack.lastOrNull()?:continue
            val id=cursor.blocks.getOrNull(cursor.index-1)?.id?:cursor.repeat?.id?:continue
            if(f.visibleWaitSlot!=null||f.waitingUntil>now()||f.waitingCondition!=null||f.waitingEvent!=null||f.pendingChildren.isNotEmpty())waiting.add(id)else active.add(id)
        }
        return RuntimeSnapshot(active,waiting,values())
    }
    fun inputs():Map<String,Value> = baseInputs.toMap()
    fun start() {
        if(started||closed)return
        val diagnostics=PipelineCodec.validate(document)
        if(diagnostics.isNotEmpty()) { diagnostics.forEach(host::diagnostic);faulted=true;return }
        val unsupported=document.requires.filterNot(host::supports)
        if(unsupported.isNotEmpty()) {fail("Unsupported requirements: ${unsupported.joinToString { "${it.capability} v${it.version}" }}");return}
        if(host.size !in document.panels) { fail("This pipeline has no variant for this panel");return }
        started=true
        refreshInputs()
        launch(document.entry()!!,"root")
        pump();compose()
    }
    private fun launch(program:Program,id:String,parent:String?=null,args:Map<String,Value> = emptyMap(),scope:String?=null,priority:Int=0) {
        if(closing)return
        if(instances.size>=limits.maxFibers) { fail("Too many running programs");return }
        val instance=Instance(id,program,parent,args,scope,priority);instances[id]=instance
        updateDemand()
        // Persistent deadlines belong to stable authored blocks, including called routines.
        // Removing a timer block from a revision cannot revive its old deadline.
        val visited=mutableSetOf<String>()
        fun restore(blocks:List<Block>) { for(b in blocks)if(b.enabled) {
            if(b.op=="timer.start"&&(b.arguments["persistent"]?.let {it.op!="literal"||it.value.boolean()}==true)) {
                val savedValue=readState(program.id,storageKey(id,"timer-scope:${b.id}"))
                val savedScope=savedValue as? Value.Record
                val literalName=(b.arguments["name"]?:BlockCatalog["timer.start"]!!.arguments.first {it.name=="name"}.default).value.text()
                val name=savedScope?.fields?.get("name")?.text()?:literalName
                val saved=savedScope?.fields?.get("deadline")?.numberOrNull()?.toLong()
                    ?:if(savedValue==null&&b.arguments["persistent"]?.value?.boolean()==true)readState(program.id,storageKey(id,"timer:$name"))?.numberOrNull()?.toLong()?:0 else 0
                if(saved>0&&name.isNotEmpty())instance.timers[name]=Timer(now()+max(0,saved-host.clock.wallMillis()),saved,true,++sequence,b.id)
            }
            if(b.op=="routine.call") {val routine=b.arguments["routine"]?.value?.text();if(routine!=null&&visited.add(routine))document.routines.find {it.id==routine}?.blocks?.let(::restore)}
            restore(b.body);restore(b.otherwise)
        } }
        program.scripts.filter {it.enabled}.forEach {restore(it.blocks)}
        dispatchTo(instance,PipelineEvent("start",atMillis=now()))
        evaluateEdges(instance,true)
        evaluateCalendar(instance)
    }
    /** True means a synchronous event handler explicitly consumed this input. */
    fun dispatch(event:PipelineEvent):Boolean {
        if(!isRunning)return false
        if(pumping) { enqueue(event);return false }
        refreshInputs()
        val consumed=deliver(event)
        pump();compose();return consumed
    }
    private fun enqueue(event:PipelineEvent) {
        if(events.size>=limits.maxQueuedEvents) { fail("Too many pending events");return }
        events.addLast(event)
    }
    fun advance() {
        if(!isRunning)return
        refreshInputs()
        var callbacks=0
        while(tasks.isNotEmpty()&&tasks.peek().due<=now()&&callbacks++<limits.maxInstructionsPerTurn) {
            val task=tasks.poll(); if(!task.cancelled)guard(null,task.action)
        }
        if(callbacks>=limits.maxInstructionsPerTurn&&tasks.peek()?.due?.let { it<=now() }==true) { fail("Scheduled callbacks did not yield");return }
        for(instance in instances.values.toList()) {
            for((name,timer) in instance.timers.toMap()) if(timer.deadline<=now()) {
                instance.timers.remove(name)
                if(timer.persistent)clearTimerState(instance,name,timer)
                dispatchTo(instance,PipelineEvent("timer",mapOf("name" to text(name)),now()))
                dispatchTo(instance,PipelineEvent("timer.$name",mapOf("name" to text(name)),now()))
            }
            evaluateEdges(instance,false)
            evaluateCalendar(instance)
        }
        if(lastTick==Long.MIN_VALUE||now()-lastTick>=50) {
            val delta=if(lastTick==Long.MIN_VALUE)50 else now()-lastTick;lastTick=now()
            for(instance in instances.values.toList())dispatchTo(instance,PipelineEvent("tick",mapOf("delta" to duration(delta)),now()))
        }
        pump();compose()
    }
    private fun refreshInputs() {
        baseInputs.clear();baseInputs.putAll(host.inputs())
        val date=java.time.Instant.ofEpochMilli(host.clock.wallMillis()).atZone(java.time.ZoneId.systemDefault())
        baseInputs["presentation.visible"]=boolean(!covered)
        baseInputs["time.elapsed"]=duration(now());baseInputs["time.wall"]=number(host.clock.wallMillis());baseInputs["time.hour"]=number(date.hour)
        baseInputs["time.minute"]=number(date.minute);baseInputs["time.second"]=number(date.second);baseInputs["time.weekday"]=number(date.dayOfWeek.value);baseInputs["panel.size"]=number(host.size)
    }
    private fun deliver(event:PipelineEvent):Boolean {
        val live=instances.values.toList()
        val focusedOwner=leases[winner]?.owner
        val recipients=if(event.consumable) {
            // Controller first; only the ancestry of the focused presentation receives input.
            // Unfocused child games must not jump when their parent's menu handles the key.
            val path=mutableSetOf("root")
            var owner=focusedOwner
            while(owner!=null) {path.add(owner);owner=instances[owner]?.parent}
            live.filter {it.id in path}.sortedBy {it.id.count {c->c=='/'}}
        } else live
        for(instance in recipients) {
            val waiting=fibers.values.filter {it.instance===instance&&it.waitingEvent==event.name}.toList()
            for(f in waiting) {f.waitingEvent=null;f.waitingUntil=0;f.event=event;f.eventValues=event.values;f.consumed=false}
            val launched=dispatchTo(instance,event)
            for(f in (waiting+launched).distinctBy {it.id}.sortedByDescending {it.script.priority})run(f)
            if(event.consumable&&(waiting+launched).any {it.consumed})return true
        }
        leases[winner]?.behavior?.let {behavior->guard(null){behavior.event(event)}}
        return false
    }
    private fun passToFocused(caller:Fiber) {
        val chain=mutableListOf<Instance>()
        var owner=leases[winner]?.owner?.let(instances::get)
        while(owner!=null&&owner.id!=caller.instance.id) {chain.add(owner);owner=owner.parent?.let(instances::get)}
        // Only descendants of the caller can receive its forwarded key.
        if(owner!=null)for(instance in chain.asReversed()) {
            val waiting=fibers.values.filter {it.instance===instance&&it.waitingEvent==caller.event.name}.toList()
            for(f in waiting){f.waitingEvent=null;f.waitingUntil=0;f.event=caller.event;f.eventValues=caller.event.values;f.consumed=false}
            val launched=dispatchTo(instance,caller.event)
            for(f in (waiting+launched).distinctBy {it.id}.sortedByDescending {it.script.priority})run(f)
            if((waiting+launched).any {it.consumed})return
        }
        leases[winner]?.behavior?.event(caller.event)
    }
    private fun dispatchTo(instance:Instance,event:PipelineEvent):List<Fiber> = instance.program.scripts
        .filter { it.enabled&&it.trigger.edge==TriggerEdge.EVENT&&it.trigger.event==event.name }
        .sortedByDescending { it.priority }.mapNotNull { s ->
            val test=Fiber(-1,instance,s,event,emptyList())
            if(s.trigger.condition?.let { eval(it,test).boolean() }==false)null else spawn(instance,s,event)
        }
    private fun evaluateEdges(instance:Instance,initial:Boolean) {
        for(script in instance.program.scripts) {
            if(!script.enabled||script.trigger.edge==TriggerEdge.EVENT)continue
            val trigger=script.trigger
            val current=trigger.condition?.let { eval(it,Fiber(-1,instance,script,PipelineEvent("condition"),emptyList())).boolean() }?:false
            val state=instance.edges.getOrPut(script.id){EdgeState()}
            if(!state.initialized) {
                state.initialized=true;state.current=current;state.candidate=current;state.since=now()
                if(initial&&trigger.initially&&current) {
                    if(trigger.stableForMs==0L)spawn(instance,script,PipelineEvent("condition",atMillis=now()))
                    else {state.current=false;state.candidate=true}
                }
                continue
            }
            if(current!=state.candidate) {state.candidate=current;state.since=now()}
            if(current==state.current||now()-state.since<trigger.stableForMs)continue
            state.current=current
            if(trigger.edge==TriggerEdge.CHANGE||trigger.edge==TriggerEdge.RISING&&current||trigger.edge==TriggerEdge.FALLING&&!current)
                spawn(instance,script,PipelineEvent("condition",mapOf("value" to boolean(current)),now()))
        }
    }
    private fun evaluateCalendar(instance:Instance) {
        val date=java.time.Instant.ofEpochMilli(host.clock.wallMillis()).atZone(java.time.ZoneId.systemDefault())
        for(script in instance.program.scripts) {
            val calendar=script.trigger.calendar?:continue
            if(!script.enabled||script.trigger.event!="calendar.daily")continue
            scheduleCalendar(instance,script,calendar,date)
            if(date.hour!=calendar.hour||date.minute!=calendar.minute||date.dayOfWeek.value !in calendar.weekdays)continue
            val today=date.toLocalDate().toEpochDay()
            val key="calendar:${script.id}"
            val last=instance.calendarDates[script.id]?:readState(instance.program.id,storageKey(instance.id,key))?.numberOrNull()?.toLong()
            // Persistent date suppresses the repeated hour after a DST fallback and process restarts.
            // Only the current minute is considered: suspension never replays a backlog.
            if(last!=null&&today<=last)continue
            val event=PipelineEvent("calendar.daily",mapOf("date" to number(today)),now())
            val test=Fiber(-1,instance,script,event,emptyList())
            if(script.trigger.condition?.let {eval(it,test).boolean()}==false)continue
            instance.calendarDates[script.id]=today
            writeState(instance.program.id,storageKey(instance.id,key),number(today))
            spawn(instance,script,event)
            scheduleCalendar(instance,script,calendar,date)
        }
    }
    private fun scheduleCalendar(instance:Instance,script:Script,calendar:CalendarTrigger,date:java.time.ZonedDateTime) {
        val key="calendar:${script.id}"
        val last=instance.calendarDates[script.id]?:readState(instance.program.id,storageKey(instance.id,key))?.numberOrNull()?.toLong()
        val next=(0L..7L).firstNotNullOfOrNull { days->
            val day=date.toLocalDate().plusDays(days)
            if(day.dayOfWeek.value !in calendar.weekdays||(last!=null&&day.toEpochDay()<=last))return@firstNotNullOfOrNull null
            val local=day.atTime(calendar.hour,calendar.minute)
            val offsets=date.zone.rules.getValidOffsets(local)
            // Missing spring-forward local times are skipped, and fallback uses its first occurrence.
            offsets.firstOrNull()?.let { local.toInstant(it).toEpochMilli() }?.takeIf {it>host.clock.wallMillis()}
        }?:return
        if(instance.calendarDeadlines[script.id]==next)return
        instance.calendarDeadlines[script.id]=next
        hostAction("calendar.schedule",mapOf("owner" to text(instance.program.id),"name" to text(storageKey(instance.id,key)),"deadline" to number(next)))
    }
    private fun spawn(instance:Instance,script:Script,event:PipelineEvent,blocks:List<Block> = script.blocks,parent:Fiber?=null):Fiber? {
        val active=instance.scriptFibers.getOrPut(script.id){mutableSetOf()}
        if(parent==null && active.isNotEmpty())when(script.reentry) {
            Reentry.IGNORE->return null
            Reentry.RESTART->active.toList().forEach { fibers[it]?.let(::cancel) }
            Reentry.QUEUE->{ val q=instance.queues.getOrPut(script.id){ArrayDeque()};if(q.size<limits.maxQueuedEvents)q.addLast(event)else fail("Too many queued handler events");return null }
            Reentry.PARALLEL->Unit
        }
        if(fibers.size>=limits.maxFibers){fail("Too many parallel scripts",script.id);return null}
        val fiber=Fiber(++sequence,instance,script,event,blocks,parent);fibers[fiber.id]=fiber;active.add(fiber.id);parent?.pendingChildren?.add(fiber.id);return fiber
    }
    private fun pump() {
        if(pumping||!isRunning)return
        pumping=true
        try {
            var deliveries=0
            do {
                for(f in fibers.values.toList())run(f)
                if(events.isNotEmpty())deliver(events.removeFirst())
            }while(isRunning&&(events.isNotEmpty()||fibers.values.any(::runnable))&&deliveries++<limits.maxQueuedEvents)
            if(deliveries>=limits.maxQueuedEvents)fail("Programs or signals did not yield")
        }finally {pumping=false;flushState()}
    }
    private fun runnable(f:Fiber):Boolean {
        if(f.stopped||f.pendingChildren.isNotEmpty())return false
        if(f.visibleWaitSlot!=null) {
            accountVisibleWait(f)
            if(f.visibleRemaining>0)return false
            f.visibleWaitSlot=null
        }
        if(f.waitingCondition!=null) {
            if(eval(f.waitingCondition!!,f).boolean()) {f.waitingCondition=null;f.waitingUntil=0}
            else if(f.waitingUntil>0&&now()>=f.waitingUntil) {f.waitingCondition=null;f.waitingUntil=0}
            else return false
        }
        if(f.waitingEvent!=null) {if(f.waitingUntil>0&&now()>=f.waitingUntil){f.waitingEvent=null;f.waitingUntil=0}else return false}
        if(f.waitingUntil>now())return false
        f.waitingUntil=0;f.resumeCleanup?.invoke();f.resumeCleanup=null
        return true
    }
    private fun run(f:Fiber) {
        if(f.overlayEnds?.let { it<=now() }==true) {cancel(f);return}
        if(!isRunning||!runnable(f))return
        var instructions=0
        while(!f.stopped&&isRunning&&instructions++<limits.maxInstructionsPerTurn) {
            if(f.stack.isEmpty()) {
                if(f.overlayEnds?.let { it>now() }==true) {f.waitingUntil=f.overlayEnds!!;break}
                finish(f);break
            }
            val cursor=f.stack.last()
            if(cursor.index>=cursor.blocks.size) {
                val repeat=cursor.repeat
                val again=when(repeat?.op) {
                    "flow.forever"->true
                    "flow.repeat"->--cursor.remaining>0
                    "flow.while"->arg(repeat,"condition",f).boolean()
                    "flow.until"->!arg(repeat,"condition",f).boolean()
                    else->false
                }
                if(again){cursor.index=0;continue}
                f.stack.removeLast();continue
            }
            val block=cursor.blocks[cursor.index++]
            if(!block.enabled)continue
            addTrace(block.id,block.op)
            guard(block.id){execute(block,f)}
            if(!runnable(f)) {f.starvation=0;break}
        }
        if(instructions>limits.maxInstructionsPerTurn&&!f.stopped) {
            f.waitingUntil=now()+16
            if(++f.starvation>=limits.maxStarvedTurns)fail("This loop needs a wait or an event",f.stack.lastOrNull()?.repeat?.id)
        }
    }
    private fun execute(b:Block,f:Fiber) {
        if(f.stack.size>=limits.maxDepth&&b.op in setOf("flow.repeat","flow.forever","flow.while","flow.until","routine.call"))throw IllegalArgumentException("Call/nesting limit exceeded")
        val evaluatedArguments=mutableMapOf<String,Value>()
        fun a(key:String)=evaluatedArguments.getOrPut(key){arg(b,key,f)}
        fun s(key:String)=a(key).text()
        fun n(key:String):Double=(a(key) as? Value.Number)?.value?:throw IllegalArgumentException("${BlockCatalog[b.op]?.title?:b.op}: $key is unavailable")
        fun nested(blocks:List<Block>) {if(f.stack.size>=limits.maxDepth)throw IllegalArgumentException("Call/nesting limit exceeded");f.stack.addLast(Cursor(blocks))}
        when(b.op) {
            "flow.sequence"->nested(b.body)
            "flow.if"->nested(if(a("condition").boolean())b.body else b.otherwise)
            "flow.select"->{val options=b.body.filter {it.enabled};if(options.isNotEmpty()) {val index=n("index").toLong();val chosen=if(a("wrap").boolean())Math.floorMod(index,options.size.toLong()).toInt()else if(index in 0L until options.size.toLong())index.toInt()else -1;options.getOrNull(chosen)?.let {nested(listOf(it))}}}
            "flow.repeat","flow.forever","flow.while","flow.until"->{val count=if(b.op=="flow.repeat")n("count").toInt().coerceIn(0,1_000_000)else 0
                if(b.op!="flow.repeat"||count>0)if(b.op!="flow.while"||a("condition").boolean())if(b.op!="flow.until"||!a("condition").boolean())
                    f.stack.addLast(Cursor(b.body,repeat=b,remaining=count))
            }
            "flow.wait"->f.waitingUntil=now()+n("duration").toLong().coerceIn(1,31_536_000_000)
            "flow.waitVisible"->{f.visibleWaitSlot="${f.instance.id}:${f.overlayPrefix}${s("slot")}";f.visibleRemaining=n("duration").toLong().coerceIn(1,31_536_000_000);f.visibleCheckedAt=now()}
            "flow.waitUntil"->{f.waitingCondition=b.arguments["condition"]?:Expression.bool(true);val timeout=n("timeout").toLong();f.waitingUntil=if(timeout>0)now()+timeout else 0}
            "flow.waitEvent"->{f.waitingEvent=s("event");val timeout=n("timeout").toLong();f.waitingUntil=if(timeout>0)now()+timeout else 0}
            "flow.parallel"->{b.body.forEach { spawn(f.instance,f.script,f.event,listOf(it),f) }}
            "flow.break"->{while(f.stack.isNotEmpty()){if(f.stack.removeLast().repeat!=null)break}}
            "flow.return"->{val result=a("value");var call:Cursor?=null;while(f.stack.isNotEmpty()){val c=f.stack.removeLast();if(c.routine!=null){call=c;break}};call?.resultVariable?.takeIf { it.isNotBlank() }?.let { setVariable(f,it,result) };if(call==null)finish(f)}
            "flow.stop"->if(s("scope")=="program")stopInstance(f.instance.id)else cancel(f)
            "routine.call"->{val r=document.routines.find { it.id==s("routine") }?:throw IllegalArgumentException("Missing routine")
                val params=r.parameters.associate { it.id to (b.arguments["arg:${it.id}"]?.let { e->eval(e,f) }?:it.default) }
                f.stack.addLast(Cursor(r.blocks,routine=r,locals=r.variables.associate { it.id to it.initial }.toMutableMap(),parameters=params,resultVariable=s("resultVariable")))
            }
            "program.run"->{val p=document.programs.find { it.id==s("program") }?:throw IllegalArgumentException("Missing program");val slot=s("slot")
                f.instance.children.remove(slot)?.let(::stopInstance)
                val id="${f.instance.id}/$slot";f.instance.children[slot]=id
                launch(p,id,f.instance.id,p.parameters.associate { it.id to (b.arguments["arg:${it.id}"]?.let { e->eval(e,f) }?:it.default) },f.overlayScope,f.overlayPriority)
            }
            "program.stop"->f.instance.children.remove(s("slot"))?.let(::stopInstance)
            "variable.set"->setVariable(f,s("variable"),a("value"))
            "variable.change"->{val old=variable(f,s("variable"));val by=a("by");setVariable(f,s("variable"),if(old is Value.Number&&by is Value.Number)Value.Number(old.value+by.value,old.unit)else Value.Unavailable("Numeric input is unavailable"))}
            "list.add","list.set","list.remove"->{val key=s("variable");val list=(variable(f,key) as? Value.Items)?.values?.toMutableList()?:mutableListOf();val index=n("index").toInt()
                when(b.op){"list.add"->{if(list.size>=limits.maxListItems)throw IllegalArgumentException("List is full");list.add(a("value"))};"list.set"->if(index in list.indices)list[index]=a("value");"list.remove"->if(index in list.indices)list.removeAt(index)}
                setVariable(f,key,Value.Items(list))
            }
            "timer.start"->{if(f.instance.timers.size>=limits.maxTimers&&s("name") !in f.instance.timers)throw IllegalArgumentException("Too many timers")
                val duration=n("duration").toLong().coerceIn(1,31_536_000_000);val persistent=a("persistent").boolean()
                val name=s("name");f.instance.timers[name]?.takeIf {it.persistent}?.let {clearTimerState(f.instance,name,it)}
                val timer=Timer(now()+duration,host.clock.wallMillis()+duration,persistent,++sequence,b.id);f.instance.timers[name]=timer
                if(persistent) {
                    writeState(f.instance.program.id,storageKey(f.instance.id,"timer-scope:${b.id}"),Value.Record(mapOf("name" to text(name),"deadline" to number(timer.wallDeadline))))
                    writeState(f.instance.program.id,storageKey(f.instance.id,"timer:$name"),number(timer.wallDeadline))
                }
            }
            "timer.cancel"->{val name=s("name");val timer=f.instance.timers.remove(name);if(timer!=null)clearTimerState(f.instance,name,timer)else writeState(f.instance.program.id,storageKey(f.instance.id,"timer:$name"),number(0))}
            "signal.emit"->{val values=(a("values") as? Value.Record)?.fields.orEmpty()+mapOf("name" to text(s("name")));enqueue(PipelineEvent("signal",values,now()));enqueue(PipelineEvent("signal.${s("name")}",values,now()))}
            "input.consume"->f.consumed=true
            "input.pass"->{passToFocused(f);f.consumed=true}
            "display.toy","display.frame","display.animation","display.off"->display(b,f)
            "display.control"->{leases["${f.instance.id}:${f.overlayPrefix}${s("slot")}"]?.let {lease->
                accountAnimation(lease)
                when(s("command")){"pause"->lease.animationPaused=true;"resume"->lease.animationPaused=false;"seek"->lease.playhead=n("position").toLong().coerceIn(0,31_536_000_000)}
                compose()
            }}
            "display.release"->release("${f.instance.id}:${f.overlayPrefix}${s("slot")}")
            "display.overlay"->{val child=spawn(f.instance,f.script,f.event,b.body,f)?:return
                val scope="overlay:${child.id}:";child.overlayScope=scope;child.ownsOverlay=true;child.overlayPrefix=scope;child.overlayPriority=n("priority").toInt();child.overlayEnds=minOf(f.overlayEnds?:Long.MAX_VALUE,now()+n("duration").toLong().coerceIn(1,31_536_000_000))
            }
            "native.command"->{val lease=leases["${f.instance.id}:${f.overlayPrefix}${s("slot")}"];lease?.behavior?.command(s("command"),(a("arguments") as? Value.Record)?.fields.orEmpty())}
            "scene.create"->{f.instance.scene.apply { slot=s("slot");priority=n("priority").toInt();clear(n("background").toInt());sprites.clear() }}
            "scene.clear"->f.instance.scene.clear(n("level").toInt())
            "scene.pixel"->f.instance.scene.pixel(n("x").toInt(),n("y").toInt(),n("level").toInt())
            "scene.line"->f.instance.scene.line(n("x").toInt(),n("y").toInt(),n("x2").toInt(),n("y2").toInt(),n("level").toInt())
            "scene.rect"->f.instance.scene.rect(n("x").toInt(),n("y").toInt(),n("width").toInt(),n("height").toInt(),n("level").toInt(),a("filled").boolean())
            "scene.circle"->f.instance.scene.circle(n("x").toInt(),n("y").toInt(),n("radius").toInt(),n("level").toInt(),a("filled").boolean())
            "scene.clip"->f.instance.scene.clip(a("enabled").boolean(),n("x").toInt(),n("y").toInt(),n("width").toInt(),n("height").toInt())
            "scene.design"->f.instance.scene.design(s("asset"),n("frame").toInt(),n("x").toInt(),n("y").toInt(),a("transparent").boolean())
            "scene.text"->f.instance.scene.text(a("text").display(),n("x").toInt(),n("y").toInt(),n("level").toInt())
            "scene.present"->{val scene=f.instance.scene;val key="${f.instance.id}:${f.overlayPrefix}${scene.slot}";val lease=leases[key]?:newLease(key,f.instance.id,scene.slot,"scene",combinedPriority(scene.priority,f.overlayPriority,f.script.priority),f.overlayScope);lease.frame=scene.render(now());compose()}
            "sprite.create"->{val binding=b.arguments["binding"]?.let { document.bindings[eval(it,f).text()] };val geometry=binding?.variants?.get(if(host.size==13)"bellsprout" else "arbok")
                f.instance.scene.create(s("name"),binding?.assetId?:s("asset"),n("x"),n("y"),n("z"),a("loop").boolean(),now(),geometry)
            }
            "sprite.set"->f.instance.scene.sprites[s("name")]?.props?.set(s("property"),a("value"))
            "sprite.control"->f.instance.scene.control(s("name"),s("command"),n("position").toLong(),now())
            "sprite.move"->{val scene=f.instance.scene;scene.sprites[s("name")]?.let { sprite->for(axis in listOf("x","y"))sprite.props[axis]=number(scene.value(s("name"),axis).number()+n(axis)) }}
            "sprite.costume"->f.instance.scene.sprites[s("name")]?.let { it.asset=s("asset");it.started=now();it.props.remove("frame") }
            "sprite.remove"->f.instance.scene.sprites.remove(s("name"))
            "sprite.step"->f.instance.scene.step(n("duration").toLong())
            "host.haptic"->hostAction("haptic",mapOf("kind" to a("kind")))
            "host.chime"->hostAction("chime",emptyMap())
            else->throw IllegalArgumentException("Unknown block ${b.op}")
        }
    }
    private fun display(b:Block,f:Fiber) {
        val spec=BlockCatalog[b.op]!!
        val args=spec.arguments.associate {it.name to eval(b.arguments[it.name]?:it.default,f)}+b.arguments.filterKeys {key->spec.arguments.none {it.name==key}}.mapValues {eval(it.value,f)}
        val slot=f.overlayPrefix+(args["slot"]?.text()?:"main");val key="${f.instance.id}:$slot"
        val signature=b.op+args.filterKeys { it!="duration"&&it!="priority" }.toString()
        val priority=combinedPriority(args["priority"]?.number()?.toInt()?:0,f.overlayPriority,f.script.priority)
        val old=leases[key]
        var lease=old
        if(old==null||old.signature!=signature||old.priority!=priority) {
            release(key,false)
            val source=when(b.op) {
                "display.frame"->{ _:Long->assets.frame(args["asset"]!!.text(),host.size,index=args["frame"]?.number()?.toInt()?:0) }
                "display.animation"->{ t:Long->assets.frame(args["asset"]!!.text(),host.size,t,args["loop"]?.boolean()?:true) }
                else->null
            }
            lease=newLease(key,f.instance.id,slot,signature,priority,f.overlayScope,source)
            if(b.op=="display.toy") {
                val owned=lease
                val bindings=b.arguments.filterKeys { it.startsWith("binding:") }.mapNotNull { (key,e)->document.bindings[eval(e,f).text()]?.let { key.removePrefix("binding:") to it } }.toMap()
                val scheduler=object:TaskScheduler { override fun post(delayMs:Long,action:()->Unit):Cancellation {
                    lateinit var cancellation:Cancellation
                    val scheduled=this@PipelineRuntime.post(delayMs){owned.cancellations.remove(cancellation);if(owned.active)action()}
                    cancellation=Cancellation {scheduled.cancel();owned.cancellations.remove(cancellation)}
                    owned.cancellations.add(cancellation);return cancellation
                } }
                val parameters=(args["parameters"] as? Value.Record)?.fields.orEmpty()
                owned.capabilities=host.nativeCapabilities(args["toy"]?.text().orEmpty(),parameters);updateDemand()
                val context=NativeContext(key,host.size,parameters,bindings,document.designs,host.clock,host.random,scheduler,{baseInputs.toMap()},
                    {frame->if(owned.active&&frame.size==host.size*host.size){owned.frame=frame.copyOf();if(winner==key)compose()}},
                    {event->if(owned.active)enqueue(event)},
                    {name,values->if(owned.active)hostAction(name,values+mapOf("owner" to text(f.instance.program.id),"instance" to text(key),"statePrefix" to text(storagePrefix(f.instance.id)),"slot" to text(slot)))},
                    {state->readState(f.instance.program.id,storageKey(f.instance.id,"native:$slot:$state"))},
                    {state,value->if(owned.active)writeState(f.instance.program.id,storageKey(f.instance.id,"native:$slot:$state"),value)},
                    {routine,state->if(owned.active)f.instance.draw(routine,state) else null})
                owned.behavior=host.createNative(args["toy"]?.text().orEmpty(),context)?:throw IllegalArgumentException("This built-in behavior is unavailable")
                owned.behavior!!.start()
            }
        }
        val duration=args["duration"]?.number()?.toLong()?:0
        if(duration>0) {val held=lease;f.waitingUntil=now()+duration;f.resumeCleanup={if(leases[key]===held)release(key)}}
        compose()
    }
    private fun combinedPriority(vararg values:Int):Int=values.sumOf {it.toLong()}.coerceIn(Int.MIN_VALUE.toLong(),Int.MAX_VALUE.toLong()).toInt()
    private fun newLease(key:String,owner:String,slot:String,signature:String,priority:Int,scope:String?,source:((Long)->IntArray?)?=null):Lease {
        val lease=Lease(key,owner,slot,signature,priority,++sequence,scope,source);leases[key]=lease;return lease
    }
    private fun release(key:String,compose:Boolean=true) {accountVisibility();leases.remove(key)?.close();updateDemand();if(compose)compose()}
    private fun checkValueBudget(value:Value) {
        val pending=ArrayDeque<Pair<Value,Int>>();pending.addLast(value to 0)
        var nodes=0;var characters=0
        while(pending.isNotEmpty()) {
            val (entry,depth)=pending.removeLast()
            require(++nodes<=8192&&depth<=limits.maxDepth){"Value exceeds its memory or nesting budget"}
            when(entry) {
                is Value.Number->require(entry.value.isFinite()&&abs(entry.value)<=1e15){"Invalid numeric value"}
                is Value.Vector->require(listOf(entry.x,entry.y,entry.z).all {it.isFinite()&&abs(it)<=1e15}){"Invalid vector"}
                is Value.Text->{require(entry.value.length<=limits.maxStringLength){"Text is too long"};characters+=entry.value.length}
                is Value.Items->{require(entry.values.size<=limits.maxListItems){"List is too long"};entry.values.forEach {pending.addLast(it to depth+1)}}
                is Value.Record->{require(entry.fields.size<=128){"Record has too many fields"};entry.fields.forEach {(key,item)->characters+=key.length;pending.addLast(item to depth+1)}}
                else->Unit
            }
            require(characters<=65536){"Value contains too much text"}
        }
    }
    private fun readState(program:String,key:String):Value?=stateWrites[program to key]?:host.readState(program,key)
    private fun writeState(program:String,key:String,value:Value) {
        checkValueBudget(value)
        require(stateWrites.size<4096||program to key in stateWrites){"Too many state writes in one update"}
        stateWrites[program to key]=value
    }
    private fun flushState() {
        if(stateWrites.isEmpty())return
        val updates=stateWrites.toMap();stateWrites.clear()
        updates.forEach {(key,value)->host.writeState(key.first,key.second,value)}
    }
    private fun clearTimerState(instance:Instance,name:String,timer:Timer) {
        timer.blockId?.let {writeState(instance.program.id,storageKey(instance.id,"timer-scope:$it"),Value.Unavailable("Timer finished or cancelled"))}
        writeState(instance.program.id,storageKey(instance.id,"timer:$name"),number(0))
    }
    private fun updateDemand() {
        if(closed)return
        val next=buildSet {
            val visitedRoutines=mutableSetOf<String>()
            fun expression(e:Expression) {if(e.op=="input")InputCatalog[e.key]?.capability?.let(::add);e.args.forEach(::expression)}
            fun blocks(blocks:List<Block>) {for(block in blocks)if(block.enabled) {
                block.arguments.values.forEach(::expression)
                block.arguments.filterKeys {it.startsWith("binding:")}.values.forEach {binding->
                    val routine=document.bindings[binding.value.text()]?.routineId
                    if(routine!=null&&visitedRoutines.add(routine))document.routines.find {it.id==routine}?.blocks?.let(::blocks)
                }
                if(block.op=="routine.call") {
                    val id=block.arguments["routine"]?.value?.text()
                    if(id!=null&&visitedRoutines.add(id))document.routines.find {it.id==id}?.blocks?.let(::blocks)
                }
                blocks(block.body);blocks(block.otherwise)
            }}
            for(instance in instances.values)for(script in instance.program.scripts)if(script.enabled) {
                EventCatalog.all.find {it.name==script.trigger.event}?.capability?.let(::add)
                script.trigger.condition?.let(::expression);blocks(script.blocks)
            }
            leases.values.filter {it.active}.forEach {addAll(it.capabilities)}
        }
        if(next!=demanded) {demanded=next;host.demand(next)}
    }
    private fun hostAction(name:String,values:Map<String,Value>) {
        val interval=when(name){"haptic"->100L;"chime"->1000L;else->0L}
        val last=hostActionTimes[name]
        if(interval>0&&last!=null&&now()-last<interval)return
        hostActionTimes[name]=now();if(name.startsWith("native.timer."))flushState();host.action(name,values)
    }
    private fun accountVisibleWait(f:Fiber) {
        if(f.visibleWaitSlot==null)return
        if(leases[f.visibleWaitSlot]?.visible==true)f.visibleRemaining=(f.visibleRemaining-(now()-f.visibleCheckedAt).coerceAtLeast(0)).coerceAtLeast(0)
        f.visibleCheckedAt=now()
    }
    private fun accountAnimation(lease:Lease) {
        if(lease.visible&&!lease.animationPaused)lease.playhead+=(now()-lease.sampledAt).coerceAtLeast(0)
        lease.sampledAt=now()
    }
    private fun accountVisibility() {fibers.values.forEach(::accountVisibleWait);leases.values.forEach(::accountAnimation)}
    private fun compose() {
        if(closed)return
        accountVisibility()
        val selected=leases.values.filter { it.active }.maxWithOrNull(compareBy<Lease>{it.priority}.thenBy{it.order})
        val changed=winner!=selected?.key;winner=selected?.key
        for(lease in leases.values.toList()) {
            val visible=lease===selected&&!covered
            if(visible!=lease.visible) {lease.visible=visible;if(visible)lease.behavior?.resume()else lease.behavior?.suspend()}
        }
        val frame=selected?.frameSource?.invoke(selected.playhead)?:selected?.frame?:IntArray(host.size*host.size)
        if(changed||lastOutput?.contentEquals(frame)!=true) {lastOutput=frame.copyOf();host.output(frame.map { it.coerceIn(0,4095) }.toIntArray())}
    }
    private fun arg(block:Block,name:String,f:Fiber):Value = eval(block.arguments[name]?:BlockCatalog[block.op]?.arguments?.find { it.name==name }?.default?:Expression.literal(Value.Unavailable()),f)
    private fun variable(f:Fiber,id:String):Value = f.stack.lastOrNull { it.locals!=null }?.locals?.get(id)?:f.instance.variables[id]?:Value.Unavailable("Unknown variable")
    private fun setVariable(f:Fiber,id:String,value:Value) {
        val call=f.stack.lastOrNull { it.locals!=null }
        val local=call?.routine?.variables?.find { it.id==id }
        val spec=local?:f.instance.program.variables.find { it.id==id }?:throw IllegalArgumentException("Unknown variable $id")
        checkValueBudget(value)
        if(!value.matches(spec.type))throw IllegalArgumentException("${spec.name} needs ${spec.type.name.lowercase()}")
        when(value){is Value.Text->require(value.value.length<=limits.maxStringLength){"Text is too long"};is Value.Items->require(value.values.size<=limits.maxListItems){"List is too long"};is Value.Number->require(value.value.isFinite()){ "Invalid arithmetic result" };else->Unit}
        if(local!=null)call!!.locals!![id]=value else {f.instance.variables[id]=value;if(spec.persistent)writeState(f.instance.program.id,storageKey(f.instance.id,id),value)}
    }
    private fun eval(e:Expression,f:Fiber,depth:Int=0):Value {
        if(depth>limits.maxDepth)return Value.Unavailable("Expression depth exceeded")
        val evaluated=mutableMapOf<Int,Value>()
        fun a(i:Int):Value=evaluated.getOrPut(i){e.args.getOrNull(i)?.let { eval(it,f,depth+1) }?:Value.Unavailable()}
        fun n(i:Int)=a(i).number()
        fun numeric(value:Double,unit:UnitKind=UnitKind.SCALAR):Value=if(value.isFinite()&&abs(value)<=1e15)Value.Number(value,unit)else Value.Unavailable("Arithmetic out of range")
        val first by lazy { a(0) };val second by lazy { a(1) }
        fun unit(i:Int)=(a(i) as? Value.Number)?.unit?:UnitKind.SCALAR
        fun combinedUnit()=if(e.args.firstOrNull()?.op=="literal"&&unit(0)==UnitKind.SCALAR)unit(1)else unit(0)
        fun equivalent(x:Value,y:Value)=if(x is Value.Number&&y is Value.Number)x.value==y.value else x==y
        if(e.op in setOf("add","subtract","multiply","divide","modulo","min","max","random","abs","negate","round","floor","ceil","sin","cos","clamp","format","between","time.range")) {
            if(e.args.indices.any { a(it) !is Value.Number })return Value.Unavailable("Numeric input is unavailable")
        }
        return when(e.op) {
            "literal"->e.value
            "input"->baseInputs[e.key]?:Value.Unavailable("${e.key} is unavailable")
            "variable"->variable(f,e.key)
            "parameter"->f.stack.lastOrNull { it.parameters!=null }?.parameters?.get(e.key)?:f.instance.parameters[e.key]?:Value.Unavailable("Unknown parameter")
            "event"->f.eventValues[e.key]?:Value.Unavailable("No event value")
            "available"->boolean(first !is Value.Unavailable)
            "choose"->if(first is Value.Unavailable)first else if(first.boolean())a(1)else a(2)
            "and"->if(first==boolean(false)||second==boolean(false))boolean(false)else if(first is Value.Unavailable||second is Value.Unavailable)Value.Unavailable()else boolean(first.boolean()&&second.boolean())
            "or"->if(first==boolean(true)||second==boolean(true))boolean(true)else if(first is Value.Unavailable||second is Value.Unavailable)Value.Unavailable()else boolean(false)
            "not"->if(first is Value.Unavailable)first else boolean(!first.boolean())
            "equal"->boolean(first !is Value.Unavailable&&second !is Value.Unavailable&&equivalent(first,second))
            "notEqual"->boolean(first !is Value.Unavailable&&second !is Value.Unavailable&&!equivalent(first,second))
            "greater","greaterEqual","less","lessEqual"->{val x=first.numberOrNull();val y=second.numberOrNull();boolean(x!=null&&y!=null&&when(e.op){"greater"->x>y;"greaterEqual"->x>=y;"less"->x<y;else->x<=y})}
            "add"->if(first is Value.Number&&second is Value.Number)numeric(n(0)+n(1),combinedUnit())else Value.Unavailable()
            "subtract"->if(first is Value.Number&&second is Value.Number)numeric(n(0)-n(1),combinedUnit())else Value.Unavailable()
            "multiply"->numeric(n(0)*n(1),if(unit(0)==UnitKind.SCALAR)unit(1)else unit(0))
            "divide"->if(n(1)==0.0)Value.Unavailable("Division by zero")else numeric(n(0)/n(1),if(unit(0)==unit(1))UnitKind.SCALAR else unit(0))
            "modulo"->if(n(1)==0.0)Value.Unavailable("Division by zero")else numeric(((n(0)%n(1))+n(1))%n(1),combinedUnit())
            "min"->numeric(min(n(0),n(1)),combinedUnit());"max"->numeric(max(n(0),n(1)),combinedUnit())
            "random"->{val low=min(n(0),n(1)).toInt();val high=max(n(0),n(1)).toInt();number(low+host.random.nextInt((high.toLong()-low+1).coerceIn(1,Int.MAX_VALUE.toLong()).toInt()))}
            "abs"->numeric(abs(n(0)),unit(0));"negate"->numeric(-n(0),unit(0));"round"->numeric(round(n(0)),unit(0));"floor"->numeric(floor(n(0)),unit(0));"ceil"->numeric(ceil(n(0)),unit(0))
            "sin"->numeric(sin(Math.toRadians(n(0))));"cos"->numeric(cos(Math.toRadians(n(0))))
            "clamp"->numeric(n(0).coerceIn(min(n(1),n(2)),max(n(1),n(2))),unit(0))
            "between"->boolean(n(0)>=min(n(1),n(2))&&n(0)<=max(n(1),n(2)))
            "time.range"->{val point=((n(0)%1440)+1440)%1440;val start=((n(1)%1440)+1440)%1440;val end=((n(2)%1440)+1440)%1440;boolean(if(start==end)true else if(start<end)point>=start&&point<end else point>=start||point<end)}
            "random.choice"->{val choices=(first as? Value.Items)?.values.orEmpty();if(choices.isEmpty())Value.Unavailable("List is empty")else choices[host.random.nextInt(choices.size)]}
            "join"->text((first.display()+second.display()).take(limits.maxStringLength))
            "format"->text(String.format(Locale.ROOT,"%.${n(1).toInt().coerceIn(0,6)}f",n(0)))
            "length"->number(when(val v=first){is Value.Text->v.value.length;is Value.Items->v.values.size;else->0})
            "list"->Value.Items(e.args.indices.take(limits.maxListItems).map { a(it) })
            "item"->(first as? Value.Items)?.values?.getOrNull(n(1).toInt())?:Value.Unavailable("List index out of range")
            "record"->Value.Record(e.args.indices.step(2).take(limits.maxListItems).associate { a(it).text() to a(it+1) })
            "field"->when(val v=first){is Value.Record->v.fields[e.key]?:Value.Unavailable();is Value.Vector->when(e.key){"x"->Value.Number(v.x,v.unit);"y"->Value.Number(v.y,v.unit);"z"->Value.Number(v.z,v.unit);else->Value.Unavailable()};else->Value.Unavailable()}
            "block.count"->authoredBlocks[e.key]?.let {number(it.body.count {block->block.enabled})}?:Value.Unavailable("Missing option block")
            "timer.running"->boolean(f.instance.timers[e.key]?.deadline?.let { it>now() }==true)
            "timer.remaining"->duration(max(0,(f.instance.timers[e.key]?.deadline?:now())-now()))
            "native.value"->leases["${f.instance.id}:${f.overlayPrefix}${first.text("main")}"]?.behavior?.values()?.get(e.key)?:Value.Unavailable("Behavior is not active")
            "sprite.value"->f.instance.scene.value(first.text(),e.key)
            "sprite.touching"->boolean(f.instance.scene.touching(first.text(),second.text()))
            "sprite.edge"->boolean(f.instance.scene.edge(first.text()))
            else->Value.Unavailable("Unknown expression")
        }
    }
    private fun post(delay:Long,action:()->Unit):Cancellation {
        val task=Task(now()+delay.coerceIn(1,31_536_000_000),++sequence,action);tasks.add(task)
        return Cancellation { task.cancelled=true;tasks.remove(task) }
    }
    private fun finish(f:Fiber) {
        f.stopped=true;fibers.remove(f.id);f.instance.scriptFibers[f.script.id]?.remove(f.id);f.parent?.pendingChildren?.remove(f.id)
        if(f.ownsOverlay)f.overlayScope?.let { scope->
            instances.values.filter { it.presentationScope==scope }.map { it.id }.forEach(::stopInstance)
            leases.values.filter { it.scope==scope }.map { it.key }.forEach(::release)
        }
        if(f.parent==null) f.instance.queues[f.script.id]?.removeFirstOrNull()?.let { spawn(f.instance,f.script,it) }
    }
    private fun cancel(f:Fiber) {f.pendingChildren.toList().forEach { fibers[it]?.let(::cancel) };f.resumeCleanup?.invoke();f.resumeCleanup=null;finish(f)}
    private fun stopInstance(id:String) {
        if(id=="root") {close();return}
        val instance=instances[id]?:return
        if(!stoppingInstances.add(id))return
        runStopHandlers(instance)
        instance.children.values.toList().forEach(::stopInstance)
        // Clear queues before cancellation so cancelled handlers cannot respawn.
        instance.calendarDeadlines.keys.forEach {hostAction("calendar.cancel",mapOf("owner" to text(instance.program.id),"name" to text(storageKey(instance.id,"calendar:$it"))))}
        instance.queues.clear();fibers.values.filter { it.instance===instance }.toList().forEach(::cancel)
        leases.values.filter { it.owner==id }.map { it.key }.forEach { release(it,false) }
        instance.drawings?.close();instances.remove(id);stoppingInstances.remove(id);updateDemand();compose()
    }
    private fun addTrace(block:String?,op:String,detail:String="") {traceLog.addLast(TraceEntry(now(),block,op,detail));while(traceLog.size>limits.maxTrace)traceLog.removeFirst()}
    private fun guard(block:String?,action:()->Unit) {try {action()}catch(e:Exception){fail(e.message?:"Pipeline operation failed",block)}}
    private fun fail(message:String,block:String?=null) {if(faulted)return;faulted=true;host.diagnostic(Diagnostic(message,block,true));addTrace(block,"error",message);close()}
    private fun runStopHandlers(instance:Instance) {
        if(!isRunning)return
        dispatchTo(instance,PipelineEvent("stop",atMillis=now())).forEach(::run)
    }
    override fun close() {
        if(closed||closing)return
        closing=true
        if(!faulted)instances.values.toList().asReversed().forEach(::runStopHandlers)
        closed=true
        instances.values.forEach {instance->instance.calendarDeadlines.keys.forEach {hostAction("calendar.cancel",mapOf("owner" to text(instance.program.id),"name" to text(storageKey(instance.id,"calendar:$it"))))}}
        fibers.clear();instances.values.forEach {it.drawings?.close()};instances.clear();events.clear();tasks.clear()
        leases.values.toList().forEach { it.close() };leases.clear();winner=null
        flushState();host.demand(emptySet());host.output(IntArray(host.size*host.size));lastOutput=null
    }
    companion object {
        fun capabilities(document:PipelineDocument):Set<String> = buildSet {
            fun expr(e:Expression) { if(e.op=="input")InputCatalog[e.key]?.capability?.let(::add);e.args.forEach(::expr) }
            fun blocks(bs:List<Block>) {for(b in bs) {
                b.arguments.values.forEach(::expr)
                if(b.op=="display.toy")when(b.arguments["toy"]?.value?.text()?.removePrefix("glyphworks.")) {
                    "visualizer"->add("audio");"weather","background.weather"->add("weather");"notifications","background.notifications"->add("notifications");"compass"->add("compass");"level","bottle"->add("orientation");"solar","background.sunrise","background.sunset"->add("location")
                    "ambient"->addAll(listOf("audio","battery","weather","notifications","location","light","accelerometer"))
                }
                blocks(b.body);blocks(b.otherwise)
            } }
            document.programs.forEach { p->p.scripts.forEach { s->EventCatalog.all.find { it.name==s.trigger.event }?.capability?.let(::add);s.trigger.condition?.let(::expr);blocks(s.blocks) } }
            document.routines.forEach { blocks(it.blocks) }
            document.requires.forEach { add(it.capability) }
        }
    }
}
