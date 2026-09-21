package ar.enganchados

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Inicializa yt-dlp y ffmpeg, que vienen empaquetados dentro del APK.
 *
 * La primera vez extraen sus binarios al almacenamiento privado, asi que
 * tarda unos segundos. Se hace aca y no en la Activity para que pase una
 * sola vez por proceso.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            listo = true
        } catch (e: Exception) {
            error = e.message ?: "No pude inicializar yt-dlp"
            Log.e("Enganchados", "init", e)
        }
    }

    companion object {
        /** Si esto es false, buscar y bajar no van a andar. */
        var listo = false
            private set
        var error: String? = null
            private set
    }
}
