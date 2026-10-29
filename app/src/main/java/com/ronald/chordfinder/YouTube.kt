package com.ronald.chordfinder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class Video(val id: String, val title: String, val channel: String, val length: String)

/**
 * Pencarian YouTube tanpa API key: baca data hasil pencarian dari halaman youtube.com.
 * Video diputar lewat pemutar resmi YouTube (IFrame), bukan diunduh.
 */
object YouTube {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = HashMap<String, Bitmap>()

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    fun search(query: String, done: (List<Video>?) -> Unit) {
        pool.execute {
            val result = try { searchBlocking(query) } catch (e: Exception) { null }
            main.post { done(result) }
        }
    }

    private fun searchBlocking(query: String): List<Video> {
        val url = URL("https://www.youtube.com/results?hl=id&gl=ID&search_query=" + URLEncoder.encode(query, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        val html = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val start = html.indexOf("ytInitialData")
        if (start < 0) return emptyList()
        val brace = html.indexOf('{', start)
        val end = html.indexOf(";</script>", brace)
        if (brace < 0 || end < 0) return emptyList()
        val data = JSONObject(html.substring(brace, end))
        val out = ArrayList<Video>()
        walk(data, out)
        return out.distinctBy { it.id }.take(10)
    }

    private fun walk(node: Any?, out: MutableList<Video>) {
        if (out.size >= 12) return
        when (node) {
            is JSONObject -> {
                val vr = node.optJSONObject("videoRenderer")
                if (vr != null) {
                    toVideo(vr)?.let { out.add(it) }
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)
        }
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    private fun toVideo(vr: JSONObject): Video? {
        val id = vr.optString("videoId").takeIf { it.length == 11 } ?: return null
        val title = text(vr.optJSONObject("title"))
        val channel = text(vr.optJSONObject("ownerText")).ifEmpty { text(vr.optJSONObject("longBylineText")) }
        val length = text(vr.optJSONObject("lengthText"))
        if (length.isEmpty()) return null // siaran langsung / bukan video biasa
        return Video(id, title, channel, length)
    }

    /** Ambil ID dari link youtube.com/watch?v=, youtu.be/, /shorts/, /embed/, atau ID langsung. */
    fun parseId(text: String): String? {
        val t = text.trim()
        Regex("(?:v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(t)?.let { return it.groupValues[1] }
        return if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) t else null
    }

    fun loadThumb(id: String, into: ImageView) {
        into.tag = id
        thumbs[id]?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        pool.execute {
            val bmp = try {
                URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            main.post {
                if (bmp != null) thumbs[id] = bmp
                if (into.tag == id && bmp != null) into.setImageBitmap(bmp)
            }
        }
    }

    /** Halaman pemutar resmi (IFrame Player API) untuk WebView. */
    fun playerHtml(id: String): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
<body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
    playerVars:{playsinline:1,autoplay:1,rel:0,modestbranding:1,origin:'$ORIGIN'},
    events:{
      onStateChange:function(e){Android.state(e.data)},
      onError:function(e){Android.error(e.data)}
    }});
}
function back10(){ if(player&&player.getCurrentTime){player.seekTo(Math.max(0,player.getCurrentTime()-10),true)} }
function toggle(){ if(!player||!player.getPlayerState)return; if(player.getPlayerState()==1){player.pauseVideo()}else{player.playVideo()} }
function pause(){ if(player&&player.pauseVideo)player.pauseVideo() }
</script></body></html>
""".trimIndent()

    const val ORIGIN = "https://com.ronald.chordfinder"
}
