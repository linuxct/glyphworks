package space.linuxct.pipeline

import kotlinx.serialization.json.*
import kotlin.math.*

/** Decoded once at construction. Rendering never parses JSON or touches a file. */
class PipelineAssets(document: PipelineDocument) {
    data class Frame(val pixels: IntArray, val durationMs: Long)
    private val assets = document.designs.mapValues { (_, d) ->
        val palette = (d["levels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: listOf(0,2048,4095)
        val variants = d["variants"] as? JsonObject ?: JsonObject(emptyMap())
        listOf(13 to "bellsprout",25 to "arbok").associate { (size,key) ->
            val frames = ((variants[key] as? JsonObject)?.get("frames") as? JsonArray).orEmpty().take(240).mapNotNull { raw ->
                val f = raw as? JsonObject ?: return@mapNotNull null
                val cells = (f["cells"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (cells.length != size*size) return@mapNotNull null
                val pixels = IntArray(size*size)
                for (i in cells.indices) { val index = cells[i].digitToIntOrNull(36) ?: return@mapNotNull null; pixels[i]=palette.getOrNull(index)?.coerceIn(0,4095) ?: return@mapNotNull null }
                Frame(pixels,((f["durationMs"] as? JsonPrimitive)?.longOrNull ?: 120).coerceIn(20,60000))
            }
            size to frames
        }
    }
    fun frames(id: String,size: Int): List<Frame> = assets[id]?.get(size).orEmpty()
    fun duration(id:String,size:Int) = frames(id,size).sumOf { it.durationMs }
    fun frame(id:String,size:Int,elapsed:Long=0,loop:Boolean=true,index:Int?=null): IntArray? {
        val frames=frames(id,size); if (frames.isEmpty()) return null
        if (index!=null) return frames[index.coerceIn(0,frames.lastIndex)].pixels
        val duration=frames.sumOf { it.durationMs }
        var time=if (loop) elapsed.coerceAtLeast(0)%duration else elapsed.coerceIn(0,duration-1)
        for (frame in frames) { if (time<frame.durationMs) return frame.pixels; time-=frame.durationMs }
        return frames.last().pixels
    }
}

internal class Scene(private val size:Int, private val assets:PipelineAssets, private val limits:RuntimeLimits) {
    data class Sprite(val name:String,var asset:String,var started:Long,var loop:Boolean=true,val props:MutableMap<String,Value> = mutableMapOf(),var geometry:SpriteGeometry?=null,var pausedAt:Long?=null)
    val sprites=linkedMapOf<String,Sprite>()
    private val pixels=IntArray(size*size)
    var slot="scene"
    var priority=0
    private var clip:IntArray?=null
    fun clip(enabled:Boolean,x:Int,y:Int,width:Int,height:Int) { clip=if(enabled)intArrayOf(x.coerceIn(0,size),y.coerceIn(0,size),(x.toLong()+width.coerceAtLeast(0)).coerceIn(0,size.toLong()).toInt(),(y.toLong()+height.coerceAtLeast(0)).coerceIn(0,size.toLong()).toInt())else null }
    private fun inside(x:Int,y:Int)=x in 0 until size&&y in 0 until size&&(clip?.let { x>=it[0]&&y>=it[1]&&x<it[2]&&y<it[3] }?:true)
    fun clear(level:Int=0) { pixels.fill(level.coerceIn(0,4095)) }
    fun pixel(x:Int,y:Int,level:Int) { if(inside(x,y)) pixels[y*size+x]=level.coerceIn(0,4095) }
    fun line(x:Int,y:Int,x2:Int,y2:Int,level:Int) {
        val count=max(abs(x2-x),abs(y2-y)).coerceAtMost(512)
        for(i in 0..count) { val t=if(count==0) 0.0 else i.toDouble()/count; pixel((x+(x2-x)*t).roundToInt(),(y+(y2-y)*t).roundToInt(),level) }
    }
    fun rect(x:Int,y:Int,width:Int,height:Int,level:Int,filled:Boolean) {
        for(j in max(0,y) until min(size,y+height.coerceIn(0,512))) for(i in max(0,x) until min(size,x+width.coerceIn(0,512)))
            if(filled||i==x||j==y||i==x+width-1||j==y+height-1) pixel(i,j,level)
    }
    fun circle(x:Int,y:Int,radius:Int,level:Int,filled:Boolean) {
        val r=radius.coerceIn(0,512).toDouble()
        for(dy in 0 until size)for(dx in 0 until size) {
            val d=hypot(dx.toDouble()-x,dy.toDouble()-y)
            if(if(filled)d<=r+0.25 else abs(d-r)<=0.5)pixel(dx,dy,level)
        }
    }
    fun design(id:String,frame:Int,x:Int,y:Int,transparent:Boolean) {
        val data=assets.frame(id,size,index=frame)?:return
        for(sy in 0 until size)for(sx in 0 until size) {
            val dx=x.toLong()+sx;val dy=y.toLong()+sy;val level=data[sy*size+sx]
            if(dx in 0 until size.toLong()&&dy in 0 until size.toLong()&&(!transparent||level>0))pixel(dx.toInt(),dy.toInt(),level)
        }
    }
    fun control(name:String,command:String,position:Long,now:Long) {
        val sprite=sprites[name]?:return
        when(command) {
            "pause"->if(sprite.pausedAt==null)sprite.pausedAt=now
            "resume"->sprite.pausedAt?.let {sprite.started+=now-it;sprite.pausedAt=null}
            "seek"->sprite.started=(sprite.pausedAt?:now)-position.coerceAtLeast(0)
        }
    }
    fun text(text:String,x:Int,y:Int,level:Int) {
        var cursor=x
        for(c in text.take(64).uppercase()) {
            if(cursor>=size) break
            val bits=font[c] ?: font['?']!!
            for(j in 0..4) for(i in 0..2) if(bits[j*3+i]=='1') pixel(cursor+i,y+j,level)
            cursor+=4
        }
    }
    fun create(name:String,asset:String,x:Double,y:Double,z:Double,loop:Boolean,now:Long,geometry:SpriteGeometry?) {
        if(name !in sprites && sprites.size>=limits.maxSprites) throw IllegalArgumentException("Too many sprites")
        sprites[name]=Sprite(name,asset,now,loop,mutableMapOf("x" to number(x),"y" to number(y),"z" to number(z),"visible" to boolean(true),"scale" to number(1)),geometry)
    }
    fun value(name:String,field:String):Value = sprites[name]?.props?.get(field) ?: when(field) { "visible" -> boolean(true); "scale" -> number(1); else -> number(0) }
    fun step(ms:Long) {
        val dt=ms.coerceIn(0,1000)/1000.0
        for(s in sprites.values) for(axis in listOf("x","y")) {
            val v="v$axis"; val a="a$axis"
            val velocity=(s.props[v]?.number()?:0.0)+(s.props[a]?.number()?:0.0)*dt
            s.props[v]=number(velocity); s.props[axis]=number((s.props[axis]?.number()?:0.0)+velocity*dt)
        }
    }
    private fun bounds(s:Sprite):DoubleArray {
        val g=s.geometry
        val raw=assets.frame(s.asset,size,0)
        var minX=size; var minY=size; var maxX=0; var maxY=0
        if(raw!=null) for(i in raw.indices) if(raw[i]>0) { minX=min(minX,i%size);maxX=max(maxX,i%size+1);minY=min(minY,i/size);maxY=max(maxY,i/size+1) }
        if(minX==size) { minX=0;minY=0;maxX=1;maxY=1 }
        val x=s.props["x"]?.number()?:0.0; val y=s.props["y"]?.number()?:0.0
        val hx=s.props["hitX"]?.number() ?: g?.hitX ?: 0.0
        val hy=s.props["hitY"]?.number() ?: g?.hitY ?: 0.0
        val w=s.props["hitWidth"]?.number() ?: g?.hitWidth?.takeIf { it>0 } ?: (maxX-minX).toDouble()
        val h=s.props["hitHeight"]?.number() ?: g?.hitHeight?.takeIf { it>0 } ?: (maxY-minY).toDouble()
        val scale=(s.props["scale"]?.number()?:1.0).coerceIn(0.1,8.0)
        return doubleArrayOf(x+hx,y+hy,x+hx+w*scale,y+hy+h*scale)
    }
    fun touching(a:String,b:String):Boolean {
        val sa=sprites[a]?:return false; val sb=sprites[b]?:return false
        if(!value(a,"visible").boolean(true)||!value(b,"visible").boolean(true)) return false
        val aa=bounds(sa); val bb=bounds(sb)
        return aa[0]<bb[2]&&aa[2]>bb[0]&&aa[1]<bb[3]&&aa[3]>bb[1]
    }
    fun edge(name:String):Boolean { val b=bounds(sprites[name]?:return false);return b[0]<0||b[1]<0||b[2]>size||b[3]>size }
    fun render(now:Long):IntArray {
        val result=pixels.copyOf()
        for(s in sprites.values.sortedBy { it.props["z"]?.number()?:0.0 }) {
            if(s.props["visible"]?.boolean(true)==false) continue
            val frame=assets.frame(s.asset,size,(s.pausedAt?:now)-s.started,s.loop,s.props["frame"]?.number()?.toInt()) ?: continue
            val g=s.geometry
            var sx=g?.x?:0;var sy=g?.y?:0;var width=g?.width?:0;var height=g?.height?:0
            if(width==0||height==0) {
                val lit=frame.indices.filter { frame[it]>0 }; if(lit.isEmpty())continue
                sx=lit.minOf { it%size };sy=lit.minOf { it/size };width=lit.maxOf { it%size }-sx+1;height=lit.maxOf { it/size }-sy+1
            }
            val x=s.props["x"]?.number()?:0.0;val y=s.props["y"]?.number()?:0.0
            val scale=(s.props["scale"]?.number()?:1.0).coerceIn(0.1,8.0);val angle=Math.toRadians(s.props["rotation"]?.number()?:0.0)
            val cosine=cos(angle);val sine=sin(angle)
            // Inverse mapping prevents holes when upscaling or rotating artwork.
            for(dy in 0 until size)for(dx in 0 until size) {
                if(!inside(dx,dy))continue
                val xx=(dx-x)/scale;val yy=(dy-y)/scale
                val px=floor(xx*cosine+yy*sine+(g?.anchorX?:0.0)).toInt()
                val py=floor(-xx*sine+yy*cosine+(g?.anchorY?:0.0)).toInt()
                if(px !in 0 until width||py !in 0 until height||sx+px !in 0 until size||sy+py !in 0 until size)continue
                val level=frame[(sy+py)*size+sx+px];if(level>0)result[dy*size+dx]=level
            }
        }
        return result
    }
    companion object {
        private val font=mapOf(
            '0' to "111101101101111",'1' to "010110010010111",'2' to "111001111100111",'3' to "111001111001111",'4' to "101101111001001",'5' to "111100111001111",'6' to "111100111101111",'7' to "111001010010010",'8' to "111101111101111",'9' to "111101111001111",
            'A' to "010101111101101",'B' to "110101110101110",'C' to "111100100100111",'D' to "110101101101110",'E' to "111100110100111",'F' to "111100110100100",'G' to "111100101101111",'H' to "101101111101101",'I' to "111010010010111",'J' to "001001001101111",'K' to "101101110101101",'L' to "100100100100111",'M' to "101111111101101",'N' to "101111111111101",'O' to "111101101101111",'P' to "111101111100100",'Q' to "111101101111001",'R' to "110101110101101",'S' to "111100111001111",'T' to "111010010010010",'U' to "101101101101111",'V' to "101101101101010",'W' to "101101111111101",'X' to "101101010101101",'Y' to "101101010010010",'Z' to "111001010100111",'?' to "111001010000010",'-' to "000000111000000",'.' to "000000000000010",':' to "000010000010000",' ' to "000000000000000",'+' to "000010111010000")
    }
}
