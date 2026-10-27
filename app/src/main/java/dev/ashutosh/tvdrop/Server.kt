package dev.ashutosh.tvdrop

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Tiny HTTP server: a phone or laptop on the same Wi-Fi opens http://<box>:8766, drops an APK
 * (PUT /upload, raw body — no multipart parsing needed) or pastes a link, then installs from there.
 */
class Server(private val app: Context) {
    fun start() {
        thread(isDaemon = true, name = "tvdrop-server") {
            runCatching {
                val server = ServerSocket(PORT)
                while (!server.isClosed) {
                    val client = server.accept()
                    thread(isDaemon = true) { client.use { runCatching { handle(it) } } }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 120_000
        val input = BufferedInputStream(client.getInputStream())
        val request = readLine(input) ?: return
        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: return
        val target = parts.getOrNull(1) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':', "").trim()
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val name = query["f"].orEmpty()

        val (status, type, body) = runCatching {
            when {
                path == "/" -> Triple(200, "text/html; charset=utf-8", PAGE)
                path == "/list" -> json(listJson())
                path == "/download" -> {
                    val url = query["url"].orEmpty().trim()
                    if (!url.startsWith("http")) error("Paste a full http(s) link")
                    json(item(Downloads.download(url)).toString())
                }
                path == "/upload" && method == "PUT" -> {
                    val length = headers["content-length"]?.toLongOrNull() ?: error("Content-Length missing")
                    val item = Downloads.receive(query["name"].orEmpty(), input, length)
                    if (item.status == Downloads.Status.FAILED) error(item.error ?: "upload failed")
                    json(item(item).toString())
                }
                path == "/install" -> {
                    if (Downloads.get(name)?.status != Downloads.Status.DONE) error("No such file")
                    app.startActivity(
                        MainActivity.open(app, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    json("""{"ok":true}""")
                }
                path == "/delete" -> { Downloads.delete(name); json("""{"ok":true}""") }
                path == "/cancel" -> { Downloads.cancel(name); json("""{"ok":true}""") }
                else -> Triple(404, "text/plain", "not found")
            }
        }.getOrElse { Triple(400, "application/json", JSONObject().put("error", it.message ?: "error").toString()) }

        val bytes = body.toByteArray()
        client.getOutputStream().use {
            it.write(
                ("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\n" +
                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
            )
            it.write(bytes)
        }
    }

    private fun json(s: String) = Triple(200, "application/json", s)

    private fun listJson(): String = JSONArray().also { arr -> Downloads.list().forEach { arr.put(item(it)) } }.toString()

    private fun item(i: Downloads.Item) = JSONObject()
        .put("name", i.name).put("status", i.status.name.lowercase()).put("done", i.done).put("total", i.total)
        .put("apk", i.isApk).put("error", i.error ?: JSONObject.NULL)

    /** Reads one header line without over-reading into the body (a Reader would buffer past it). */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 16384) return null
        }
    }

    companion object {
        const val PORT = 8766

        fun address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress?.let { "http://$it:$PORT" }
        }.getOrNull()

        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TVDrop</title>
<style>
  body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:20px;max-width:640px}
  h1{font-size:20px;margin:0 0 16px}
  #drop{border:2px dashed #444;border-radius:12px;padding:28px;text-align:center;color:#aaa;cursor:pointer}
  #drop.over{border-color:#2d6cdf;color:#eee;background:#16203a}
  #drop input{display:none}
  form{display:flex;gap:8px;margin:14px 0}
  input[type=url]{flex:1;min-width:0;padding:12px;font-size:16px;border-radius:10px;border:1px solid #444;background:#1c1c1c;color:#eee}
  button{padding:12px 16px;font-size:15px;border:0;border-radius:10px;background:#2d6cdf;color:#fff}
  button.g{background:#2a2a2a} button.r{background:#7a2727}
  label.c{display:block;margin:8px 0;color:#aaa;font-size:14px}
  ul{list-style:none;padding:0;margin:16px 0}
  li{background:#1a1a1f;border-radius:10px;padding:12px;margin-bottom:8px}
  .n{font-weight:600;word-break:break-all} .s{color:#888;font-size:13px;margin:4px 0}
  .bar{height:6px;background:#333;border-radius:3px;overflow:hidden;margin:6px 0}
  .bar div{height:100%;background:#2d6cdf}
  .err{color:#f88}
  li button{margin:6px 6px 0 0;padding:8px 12px;font-size:14px}
  #status{color:#8ab4ff;font-size:14px;min-height:18px}
</style></head><body>
<h1>TVDrop</h1>
<div id="drop">Drop an APK here or tap to choose<input type="file" id="file" multiple></div>
<label class="c"><input type="checkbox" id="auto" checked> Open the installer on the TV after upload</label>
<div id="status"></div>
<form id="urlf"><input type="url" name="url" placeholder="or paste a download link" required><button>Download</button></form>
<ul id="list"></ul>
<script>
const q=s=>document.querySelector(s), enc=encodeURIComponent;
const human=n=>n<0?'?':n<1024?n+' B':n<1048576?(n/1024).toFixed(0)+' KB':(n/1048576).toFixed(1)+' MB';
const el=(t,c,x)=>{const e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e};
async function post(p){const r=await fetch(p,{method:'POST'});const j=await r.json().catch(()=>({}));if(j.error)q('#status').textContent=j.error;refresh()}
async function refresh(){
  const items=await (await fetch('/list')).json(), ul=q('#list'); ul.innerHTML='';
  if(!items.length){ul.appendChild(el('li','s','Nothing on the TV yet'));return}
  for(const i of items){
    const li=el('li'); li.appendChild(el('div','n',i.name));
    const busy=i.status=='downloading'||i.status=='uploading';
    if(busy){
      li.appendChild(el('div','s',(i.status=='uploading'?'Receiving ':'Downloading ')+human(i.done)+(i.total>0?' of '+human(i.total):'')));
      const b=el('div','bar'),f=el('div');f.style.width=(i.total>0?Math.round(i.done*100/i.total):5)+'%';b.appendChild(f);li.appendChild(b);
      const c=el('button','g','Cancel');c.onclick=()=>post('/cancel?f='+enc(i.name));li.appendChild(c);
    }else if(i.status=='failed'){
      li.appendChild(el('div','s err','Failed: '+i.error));
      const d=el('button','r','Remove');d.onclick=()=>post('/delete?f='+enc(i.name));li.appendChild(d);
    }else{
      li.appendChild(el('div','s',human(i.total)));
      const b=el('button','',i.apk?'Install on TV':'Open on TV');b.onclick=()=>post('/install?f='+enc(i.name));li.appendChild(b);
      const d=el('button','r','Delete');d.onclick=()=>{if(confirm('Delete '+i.name+'?'))post('/delete?f='+enc(i.name))};li.appendChild(d);
    }
    ul.appendChild(li);
  }
}
function upload(file){
  const x=new XMLHttpRequest(); x.open('PUT','/upload?name='+enc(file.name));
  x.upload.onprogress=e=>{q('#status').textContent='Uploading '+file.name+' '+Math.round(e.loaded*100/e.total)+'%';refresh()};
  x.onload=()=>{ if(x.status!=200){q('#status').textContent='Upload failed: '+x.responseText;return}
    q('#status').textContent='Uploaded '+file.name; refresh();
    if(q('#auto').checked&&/\.apk$/i.test(file.name)) post('/install?f='+enc(JSON.parse(x.responseText).name)) };
  x.onerror=()=>q('#status').textContent='Upload failed';
  x.send(file);
}
const drop=q('#drop');
drop.onclick=()=>q('#file').click();
q('#file').onchange=e=>{[...e.target.files].forEach(upload);e.target.value=''};
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};
drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');[...e.dataTransfer.files].forEach(upload)};
q('#urlf').onsubmit=e=>{e.preventDefault();post('/download?url='+enc(e.target.url.value));e.target.url.value=''};
setInterval(refresh,1000); refresh();
</script></body></html>
"""
    }
}
