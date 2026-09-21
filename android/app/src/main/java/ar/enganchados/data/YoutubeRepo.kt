package ar.enganchados.data

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Busqueda y descarga con yt-dlp, que viene empaquetado en la app.
 *
 * Misma politica que la version de escritorio: se baja el stream NATIVO
 * sin re-encodear. YouTube no tiene audio sin perdida —su maximo ronda
 * los 130 kbps— asi que convertirlo a "mp3 320" no agrega informacion:
 * solo agrega perdida y peso.
 */
object YoutubeRepo {

    const val TAG = "Enganchados"

    /** Deja el error entero en logcat: el cartelito de la UI lo recorta. */
    private fun registrar(accion: String, e: Throwable) {
        Log.e(TAG, "$accion fallo: ${e.message}", e)
    }

    /**
     * Devuelve candidatos SIN bajar nada.
     *
     * Existe porque `ytsearch1` agarra siempre el primer resultado, y el
     * primero no suele ser el bueno: hay covers, remixes, clases de
     * gimnasia y hasta otra banda con el mismo titulo. Elige el usuario.
     */
    /**
     * Una pagina de resultados, de [desde] a [hasta] (1-based, inclusive).
     *
     * yt-dlp no tiene offset real para busquedas: pide `ytsearchN` y recorta
     * con `-I`. O sea que la pagina 5 igual recorre las 4 anteriores por
     * dentro, y cada pagina nueva tarda un poco mas. Medido: pidiendo 100
     * vuelven ~90, porque YouTube filtra canales y playlists.
     */
    suspend fun buscar(consulta: String, desde: Int = 1, hasta: Int = 20): List<Candidato> =
        withContext(Dispatchers.IO) {
            if (consulta.isBlank()) return@withContext emptyList()

            val pedido = YoutubeDLRequest("ytsearch$hasta:$consulta").apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("-I", "$desde:$hasta")
                addOption("--no-warnings")
                addOption("--quiet")
            }

            val salida = runCatching { YoutubeDL.getInstance().execute(pedido).out }
                .onFailure { registrar("buscar '$consulta'", it) }
                .getOrThrow()

            salida.lineSequence()
                .mapNotNull { linea ->
                    runCatching { JSONObject(linea.trim()) }.getOrNull()
                }
                .mapNotNull { o ->
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Candidato(
                        id = id,
                        titulo = o.optString("title", "(sin titulo)"),
                        canal = o.optString("channel").ifBlank { o.optString("uploader", "?") },
                        duracion = o.optDouble("duration", 0.0).toInt(),
                        vistas = o.optLong("view_count", 0L),
                    )
                }
                .toList()
        }

    /**
     * Baja un tema a [destino]. Reporta avance 0..100 por [avance].
     */
    suspend fun bajar(
        candidato: Candidato,
        destino: File,
        avance: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val carpeta = destino.parentFile!!.apply { mkdirs() }

        val pedido = YoutubeDLRequest(candidato.url).apply {
            // El stream nativo tal cual, sin re-encodear.
            addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            addOption("-x")
            addOption("--audio-format", "best")
            addOption("--embed-metadata")
            // Que una busqueda nunca arrastre un "mix" o playlist entera
            addOption("--no-playlist")
            addOption("--match-filter", "duration < 900")
            addOption("--retries", "5")
            addOption("-o", File(carpeta, "${candidato.id}.%(ext)s").absolutePath)
        }

        runCatching {
            YoutubeDL.getInstance().execute(pedido) { progreso, _, _ ->
                avance(progreso.coerceIn(0f, 100f))
            }
        }.onFailure { registrar("bajar ${candidato.id}", it) }.getOrThrow()

        // yt-dlp decide la extension final segun el stream que encontro
        carpeta.listFiles()
            ?.firstOrNull { it.nameWithoutExtension == candidato.id }
            ?: throw IllegalStateException("No quedo archivo para ${candidato.titulo}")
    }

    /**
     * Actualiza el binario de yt-dlp desde la app.
     *
     * YouTube cambia seguido y yt-dlp se rompe; esto permite arreglarlo
     * sin tener que redistribuir el APK.
     */
    suspend fun actualizar(contexto: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            val estado = YoutubeDL.getInstance()
                .updateYoutubeDL(contexto, YoutubeDL.UpdateChannel._STABLE)
            val version = YoutubeDL.getInstance().versionName(contexto)
            Log.i(TAG, "yt-dlp: $estado, version $version")
            "yt-dlp $version (${estado?.name?.lowercase() ?: "sin cambios"})"
        }.getOrElse {
            registrar("actualizar yt-dlp", it)
            "No se pudo actualizar: ${it.message}"
        }
    }
}
