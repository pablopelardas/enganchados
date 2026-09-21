package ar.enganchados.data

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Arma el enganchado final.
 *
 * Nunca se junta la mezcla entera en memoria: 25 minutos en estereo son
 * mas de medio giga de PCM y el sistema mata la app. Se escribe a disco
 * a medida que avanza y solo se retiene la cola del cruce.
 */
object Mezcla {

    private const val CANALES = 2
    private val SR = Audio.SR_SALIDA

    /**
     * Nivel objetivo por tramo.
     *
     * No es EBU R128 como el loudnorm de la version de escritorio: es
     * RMS, mas simple. Pero resuelve lo que importa en una fiesta, que
     * es que todos los temas suenen parejo. Cada upload de YouTube viene
     * con un nivel distinto y sin esto la mezcla salta de susurro a
     * bocinazo entre tema y tema.
     */
    private const val RMS_OBJETIVO = 0.12f
    private const val GANANCIA_MAX = 4.0f

    suspend fun renderizar(
        enganchado: Enganchado,
        almacen: Almacen,
        destino: File,
        avance: (Int, Int) -> Unit = { _, _ -> },
        codificando: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val temas = enganchado.temas.filter { it.bajado && it.largo > 0.5 }
        require(temas.size >= 2) { "Hacen falta al menos 2 temas bajados" }

        val cruceN = (enganchado.cruce * SR).toInt() * CANALES
        val temporal = File(destino.parentFile, "_mezcla.wav")
        val wav = WavStream(temporal, SR, CANALES)

        var cola: FloatArray? = null

        try {
            temas.forEachIndexed { i, tema ->
                avance(i + 1, temas.size)

                val seg = tramoDe(tema, almacen, enganchado.cruce)
                if (seg.isEmpty()) return@forEachIndexed

                val c = minOf(cruceN, seg.size / 2)

                if (cola == null) {
                    wav.escribir(seg, 0, maxOf(0, seg.size - c))
                } else {
                    // El cruce: la cola del anterior se funde con la
                    // cabeza de este. De ahi que no queden huecos.
                    val previo = cola!!
                    val n = minOf(previo.size, c)
                    val fundido = FloatArray(n)
                    for (j in 0 until n) {
                        val t = j.toFloat() / n
                        fundido[j] = previo[j] * (1 - t) + seg[j] * t
                    }
                    wav.escribir(fundido, 0, n)
                    wav.escribir(seg, n, maxOf(n, seg.size - c))
                }

                cola = if (seg.size > c) seg.copyOfRange(seg.size - c, seg.size) else null
            }

            cola?.let { wav.escribir(it, 0, it.size) }
        } finally {
            wav.cerrar()
        }

        aM4a(temporal, destino, codificando)
        temporal.delete()
        destino
    }

    /** Corta el tramo del tema y lo deja nivelado, en estereo a 44.1k. */
    private fun tramoDe(tema: Tema, almacen: Almacen, cruce: Double): FloatArray {
        val archivo = File(tema.archivo ?: return FloatArray(0))
        if (!archivo.exists()) return FloatArray(0)

        // Se pide un poco mas de audio del que se ve: el cruce se come
        // `cruce` segundos en la union, asi que sin ese colchon
        // perderiamos el final de cada tramo.
        val hastaSeg = minOf(tema.fin + cruce, tema.duracion)

        // Solo el tramo: decodificar el tema entero para quedarse con un
        // minuto no entra en memoria con temas largos.
        val pcm = Audio.decodificarTramo(archivo, tema.inicio, hastaSeg)
            ?: return FloatArray(0)

        val desde = 0
        val hasta = pcm.muestras.size / pcm.canales
        if (hasta <= desde) return FloatArray(0)

        // a estereo
        val n = hasta - desde
        val est = FloatArray(n * CANALES)
        for (i in 0 until n) {
            val base = (desde + i) * pcm.canales
            val izq = pcm.muestras[base]
            val der = if (pcm.canales > 1) pcm.muestras[base + 1] else izq
            est[i * 2] = izq
            est[i * 2 + 1] = der
        }

        val remuestreado = remuestrearEstereo(est, pcm.sampleRate, SR)
        nivelar(remuestreado)
        return remuestreado
    }

    private fun remuestrearEstereo(x: FloatArray, srOrigen: Int, srDestino: Int): FloatArray {
        if (srOrigen == srDestino) return x
        val cuadros = x.size / CANALES
        val razon = srOrigen.toDouble() / srDestino
        val nuevos = (cuadros / razon).toInt()
        val out = FloatArray(nuevos * CANALES)
        for (i in 0 until nuevos) {
            val pos = i * razon
            val a = pos.toInt().coerceIn(0, cuadros - 1)
            val b = (a + 1).coerceAtMost(cuadros - 1)
            val f = (pos - a).toFloat()
            for (c in 0 until CANALES) {
                out[i * CANALES + c] =
                    x[a * CANALES + c] * (1 - f) + x[b * CANALES + c] * f
            }
        }
        return out
    }

    /** Lleva el tramo al nivel objetivo, sin pasarse de 0 dBFS. */
    private fun nivelar(x: FloatArray) {
        if (x.isEmpty()) return
        var suma = 0.0
        for (v in x) suma += v.toDouble() * v
        val rms = sqrt(suma / x.size).toFloat()
        if (rms < 1e-6f) return

        val ganancia = (RMS_OBJETIVO / rms).coerceAtMost(GANANCIA_MAX)
        for (i in x.indices) {
            // clamp suave: antes reventar el pico que distorsionar todo
            x[i] = (x[i] * ganancia).coerceIn(-0.99f, 0.99f)
        }
    }

    // ------------------------------------------------------- WAV en streaming

    /** Escribe WAV mientras se genera y parchea el tamaño al cerrar. */
    private class WavStream(destino: File, sr: Int, canales: Int) {
        private val f = RandomAccessFile(destino, "rw")
        private var bytes = 0

        init {
            f.setLength(0)
            f.write("RIFF".toByteArray()); f.writeInt(0)
            f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le(16))
            f.write(le16(1)); f.write(le16(canales))
            f.write(le(sr)); f.write(le(sr * canales * 2))
            f.write(le16(canales * 2)); f.write(le16(16))
            f.write("data".toByteArray()); f.writeInt(0)
        }

        fun escribir(x: FloatArray, desde: Int, hasta: Int) {
            if (hasta <= desde) return
            val n = hasta - desde
            val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in desde until hasta) {
                buf.putShort((x[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            f.write(buf.array())
            bytes += n * 2
        }

        fun cerrar() {
            f.seek(4); f.write(le(36 + bytes))
            f.seek(40); f.write(le(bytes))
            f.close()
        }

        private fun le(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())

        private fun le16(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
    }

    // --------------------------------------------------- WAV -> m4a en streaming

    /**
     * Codifica leyendo del WAV por partes, sin juntarlo todo en RAM.
     *
     * Mismo patron que la decodificacion: meter todo lo que el codec acepte,
     * sacar todo lo que tenga listo, y dormir un instante solo si no paso
     * nada. La version anterior esperaba 10 ms fijos por vuelta y codificaba
     * a menos de 2x tiempo real: 14 minutos de mezcla tardaban ~7.
     */
    private fun aM4a(wav: File, destino: File, avance: (Float) -> Unit) {
        val formato = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SR, CANALES
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var pista = -1
        var arrancado = false

        val entrada = BufferedInputStream(FileInputStream(wav), 1 shl 16)
        entrada.skip(44)   // cabecera WAV
        val totalBytes = (wav.length() - 44).coerceAtLeast(1)

        val info = MediaCodec.BufferInfo()
        var bytes = ByteArray(16 * 1024)   // se reusa: nada de un arreglo por vuelta
        var leidosTotal = 0L
        var muestras = 0L
        var finEntrada = false
        var terminado = false
        var esperas = 0
        var ultimoPorcentaje = -1
        val porUs = 1_000_000.0 / (SR * CANALES)

        try {
            while (!terminado) {
                var avanzo = false

                while (!finEntrada) {
                    val idx = codec.dequeueInputBuffer(0)
                    if (idx < 0) break
                    val buf = codec.getInputBuffer(idx)!!
                    buf.clear()
                    if (bytes.size < buf.capacity()) bytes = ByteArray(buf.capacity())

                    val leidos = leerCuadrosEnteros(entrada, bytes, buf.capacity())
                    if (leidos <= 0) {
                        codec.queueInputBuffer(idx, 0, 0, (muestras * porUs).toLong(),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        finEntrada = true
                    } else {
                        buf.put(bytes, 0, leidos)
                        codec.queueInputBuffer(idx, 0, leidos, (muestras * porUs).toLong(), 0)
                        muestras += leidos / 2
                        leidosTotal += leidos

                        val porcentaje = (leidosTotal * 100 / totalBytes).toInt()
                        if (porcentaje != ultimoPorcentaje) {
                            ultimoPorcentaje = porcentaje
                            avance(porcentaje / 100f)
                        }
                    }
                    avanzo = true
                }

                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        pista = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        arrancado = true
                        continue
                    }
                    if (idx < 0) continue
                    avanzo = true

                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.size > 0 && arrancado &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        muxer.writeSampleData(pista, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        terminado = true
                        break
                    }
                }

                if (avanzo) {
                    esperas = 0
                } else {
                    if (finEntrada && ++esperas > 1000) break   // tope: ~2 s sin salida
                    Thread.sleep(2)
                }
            }
        } finally {
            entrada.close()
            codec.stop()
            codec.release()
            if (arrancado) muxer.stop()
            muxer.release()
        }
    }

    /**
     * Lee hasta llenar [max] bytes o llegar al final, y devuelve una cantidad
     * multiplo de un cuadro estereo (4 bytes). Un read() suelto puede cortar a
     * mitad de una muestra, y el encoder recibiria ruido corrido un byte.
     */
    private fun leerCuadrosEnteros(entrada: BufferedInputStream, destino: ByteArray, max: Int): Int {
        val cuadro = 2 * CANALES
        val objetivo = (max / cuadro) * cuadro
        var total = 0
        while (total < objetivo) {
            val n = entrada.read(destino, total, objetivo - total)
            if (n < 0) break
            total += n
        }
        return (total / cuadro) * cuadro
    }
}
