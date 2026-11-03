package app.futbolio.engine.adapters.stremio.server

import app.futbolio.application.api.HostConfig
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.Text
import kotlinx.serialization.json.JsonPrimitive

internal object StremioPages {
    fun install(host: HostConfig) = """<!doctype html><html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Futbolio</title><body style="font:16px system-ui;background:#0b1210;color:#eef6f0;padding:24px"><h1>Futbolio</h1><p>Live football discovery for Stremio.</p><a style="color:#8dedb8" href="${Text.html(host.manifest.replace("http://", "stremio://"))}">Install in Stremio</a><p>Manifest: ${Text.html(host.manifest)}</p><p>Keep Futbolio running during playback. ${if (host.desktop) "Share this installation URL only with trusted local devices." else "This address works only on this device."}</p><p>Use the Futbolio app to edit providers and view diagnostics. Website options open externally. Use only streams you are authorized to access.</p></body></html>"""

    fun workbench(host: HostConfig, sources: List<SourceConfig>): String {
        val options = sources.joinToString("") { "<option value='${Text.html(it.id)}'>${Text.html(it.name)}</option>" }
        return """<!doctype html><html><meta name=viewport content='width=device-width,initial-scale=1'><title>Futbolio Provider Workbench</title><body style='font:16px system-ui;background:#0b1210;color:#eef6f0;padding:24px;max-width:850px;margin:auto'><h1>Provider Workbench</h1><p>No rebuild needed. Edit provider URLs and recovery settings in Futbolio, then test discovery, resolution and media segments here.</p><select id=p>$options</select> <button id=run>Test provider</button><p>After testing, open the Provider Tests catalog in Stremio. Segment success does not prove video/audio playback. Confirm playback in the native Futbolio app after watching it.</p><pre id=report style='white-space:pre-wrap;overflow-wrap:anywhere'></pre><script>const base=${JsonPrimitive(host.base)};let timer;async function refresh(){const r=await fetch(base+'/workbench/reports.json').then(r=>r.json());document.getElementById('report').textContent=JSON.stringify(r,null,2);if(Object.values(r).some(r=>!['complete','failed','cancelled'].includes(r.phase)))timer=setTimeout(refresh,1500)}document.getElementById('run').onclick=async()=>{clearTimeout(timer);await fetch(base+'/workbench/test/'+document.getElementById('p').value+'.json',{headers:{'X-Futbolio-Action':'test'}});setTimeout(refresh,300)};refresh();</script></body></html>"""
    }
}
