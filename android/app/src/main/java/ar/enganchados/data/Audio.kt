package ar.enganchados.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Decodificar audio con MediaCodec, SIEMPRE en streaming.
 *
 * La primera version decodificaba el tema entero a PCM y despues lo
 * procesaba. Un tema de 14 minutos en estereo a 44.1 kHz son ~290 MB, y
 * juntar los pedazos pedia otro tanto: OutOfMemoryError. Ahora el audio
 * pasa por bloques y cada uso se queda solo con lo que necesita.
 *
 * No se usa ffmpeg a proposito: el modulo que trae youtubedl-android esta
 * para que yt-dlp lo use por dentro, sin garantia de ejecucion arbitraria.
 */
object Audio {

    const val SR_ANALISIS = 22050
    const val SR_SALIDA = 44100

    class Pcm(val muestras: FloatArray, val sampleRate: Int, val canales: Int)

    class Envolvente(val rms: FloatArray, val duracion: Double)

    /**
     * Recorre el archivo bloque por bloque y entrega cada uno ya decodificado.
     *
     * [alRecibir] devuelve false para cortar antes: asi cortar un tramo no
     * obliga a decodificar hasta el final del tema.
     */
    private fun recorrer(
        archivo: File,
        desdeUs: Long = 0L,
        alRecibir: (bloque: FloatArray, sr: Int, canales: Int, tiempoUs: Long) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(archivo.absolutePath)

        val pista = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: run { extractor.release(); return }

        val entrada = extractor.getTrackFormat(pista)
        extractor.selectTrack(pista)
        if (desdeUs > 0) extractor.seekTo(desdeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        // Arrancan con lo que dice el contenedor, pero la verdad la dice el
        // decoder al emitir: con HE-AAC, por ejemplo, el sample rate real es
        // el doble del declarado.
        var sr = entrada.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var canales = entrada.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var flotante = false

        val codec = MediaCodec.createDecoderByType(entrada.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(entrada, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var finEntrada = false
        var esperasVacias = 0

        try {
            var terminado = false
            while (!terminado) {
                var avanzo = false

                // Entrada: todo lo que el codec acepte AHORA, sin esperar.
                while (!finEntrada) {
                    val idx = codec.dequeueInputBuffer(0)
                    if (idx < 0) break
                    val buf = codec.getInputBuffer(idx)!!
                    val leidos = extractor.readSampleData(buf, 0)
                    if (leidos < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        finEntrada = true
                    } else {
                        codec.queueInputBuffer(idx, 0, leidos, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    avanzo = true
                }

                // Salida: todo lo que ya este listo, sin esperar.
                // La version anterior esperaba 10 ms por vuelta en cada lado:
                // ese costo fijo dejaba todo a ~2x tiempo real.
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        canales = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        flotante = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        continue
                    }
                    if (idx < 0) continue
                    avanzo = true

                    var seguir = true
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(idx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bloque = if (flotante) leerFloat(buf) else leerShort(buf)
                        seguir = alRecibir(bloque, sr, canales, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (!seguir || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        terminado = true
                        break
                    }
                }

                if (avanzo) {
                    esperasVacias = 0
                } else {
                    // Nada que hacer por ahora: un respiro corto, no 10 ms fijos.
                    // Y tope por si el decoder nunca emite fin de stream (~2 s).
                    if (finEntrada && ++esperasVacias > 1000) break
                    Thread.sleep(2)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }

    private fun leerShort(buf: ByteBuffer): FloatArray {
        val s = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(s.remaining()) { s.get() / 32768f }
    }

    private fun leerFloat(buf: ByteBuffer): FloatArray {
        val f = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(f.remaining()) { f.get() }
    }

    /**
     * La envolvente de energia, sin guardar ni una muestra.
     *
     * Es lo unico que necesita el analisis: un valor por cuadro de HOP
     * muestras a 22050 Hz (~23 ms). Para 14 minutos son ~36.000 floats,
     * 144 KB, en vez de los 580 MB que pedia decodificar todo.
     *
     * No se remuestrea: la ventana se mide en TIEMPO en el sample rate
     * original, que da el mismo resultado sin el costo.
     */
    fun envolvente(archivo: File): Envolvente {
        var salida = FloatArray(4096)
        var usados = 0

        var suma = 0.0
        var enVentana = 0
        var total = 0L
        var srVisto = 0
        var ventana = 0.0        // largo de la ventana en muestras del original
        var siguienteCorte = 0.0 // acumulado en double para no derivar con 48 kHz

        recorrer(archivo) { bloque, sr, canales, _ ->
            if (sr != srVisto) {
                srVisto = sr
                ventana = sr.toDouble() * Analisis.HOP / SR_ANALISIS
                if (siguienteCorte == 0.0) siguienteCorte = ventana
            }
            val cuadros = bloque.size / canales
            for (i in 0 until cuadros) {
                var m = 0f
                for (c in 0 until canales) m += bloque[i * canales + c]
                m /= canales
                suma += m.toDouble() * m
                enVentana++
                total++
                if (total >= siguienteCorte) {
                    if (usados == salida.size) salida = salida.copyOf(salida.size * 2)
                    salida[usados++] = sqrt(suma / enVentana).toFloat()
                    suma = 0.0
                    enVentana = 0
                    siguienteCorte += ventana
                }
            }
            true
        }

        val duracion = if (srVisto > 0) total.toDouble() / srVisto else 0.0
        return Envolvente(salida.copyOf(usados), duracion)
    }

    /**
     * Solo el pedazo [desde, hasta] en segundos. Salta directo al principio
     * del tramo y deja de decodificar apenas lo pasa: un tramo de un minuto
     * cuesta un minuto de audio, no el tema entero.
     */
    fun decodificarTramo(archivo: File, desde: Double, hasta: Double): Pcm? {
        val desdeUs = (desde * 1_000_000).toLong()
        val hastaUs = (hasta * 1_000_000).toLong()
        val partes = ArrayList<FloatArray>()
        var srFinal = 0
        var canalesFinal = 0

        recorrer(archivo, desdeUs) { bloque, sr, canales, tUs ->
            srFinal = sr
            canalesFinal = canales
            val cuadros = bloque.size / canales
            val finBloqueUs = tUs + cuadros * 1_000_000L / sr

            // el seek cae en el sync anterior: lo previo al tramo se descarta
            if (finBloqueUs <= desdeUs) return@recorrer true

            val ini = if (tUs < desdeUs) ((desdeUs - tUs) * sr / 1_000_000L).toInt() else 0
            val fin = if (finBloqueUs > hastaUs)
                ((hastaUs - tUs) * sr / 1_000_000L).toInt().coerceIn(0, cuadros)
            else cuadros

            if (fin > ini) partes.add(bloque.copyOfRange(ini * canales, fin * canales))
            finBloqueUs < hastaUs
        }

        if (partes.isEmpty()) return null
        val todo = FloatArray(partes.sumOf { it.size })
        var p = 0
        for (parte in partes) { parte.copyInto(todo, p); p += parte.size }
        return Pcm(todo, srFinal, canalesFinal)
    }

    // ------------------------------------------------------------- WAV

    /** WAV PCM 16 bits: lo entiende cualquier DAW sin preguntar. */
    fun escribirWav(destino: File, muestras: FloatArray, sampleRate: Int, canales: Int) {
        val bytesDatos = muestras.size * 2
        RandomAccessFile(destino, "rw").use { f ->
            f.setLength(0)
            f.write("RIFF".toByteArray()); f.write(intLe(36 + bytesDatos))
            f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(intLe(16))
            f.write(shortLe(1)); f.write(shortLe(canales))
            f.write(intLe(sampleRate)); f.write(intLe(sampleRate * canales * 2))
            f.write(shortLe(canales * 2)); f.write(shortLe(16))
            f.write("data".toByteArray()); f.write(intLe(bytesDatos))

            val buf = ByteBuffer.allocate(bytesDatos).order(ByteOrder.LITTLE_ENDIAN)
            for (m in muestras) buf.putShort((m.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            f.write(buf.array())
        }
    }

    private fun intLe(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())

    private fun shortLe(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
}
