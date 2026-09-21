package ar.enganchados.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exporta el enganchado desarmado: cada tramo como WAV suelto mas un
 * proyecto de Reaper con todo ya ubicado en la linea de tiempo, todo
 * dentro de un ZIP para mandarlo a la PC.
 *
 * El m4a mezclado es un callejon sin salida: son los temas aplastados en
 * dos canales y no se puede desarmar. Para ponerle un beat encima hacen
 * falta los pedazos sueltos.
 */
object Reaper {

    private data class Puesto(
        val archivo: String,
        val titulo: String,
        val posicion: Double,
        val largo: Double,
        val primero: Boolean,
        val ultimo: Boolean,
    )

    suspend fun exportar(
        enganchado: Enganchado,
        almacen: Almacen,
        avance: (Int, Int) -> Unit = { _, _ -> },
        empaquetando: () -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val temas = enganchado.temas.filter { it.bajado && it.largo > 0.5 }
        require(temas.isNotEmpty()) { "No hay temas bajados" }

        val carpeta = File(almacen.exportes, enganchado.nombre).apply {
            deleteRecursively(); mkdirs()
        }

        val puestos = mutableListOf<Puesto>()
        var posicion = 0.0

        temas.forEachIndexed { i, tema ->
            avance(i + 1, temas.size)

            val nombre = "%02d.wav".format(i)
            val muestras = tramoEstereo(tema, enganchado.cruce)
            if (muestras.isEmpty()) return@forEachIndexed

            Audio.escribirWav(File(carpeta, nombre), muestras, Audio.SR_SALIDA, 2)
            val largo = muestras.size / 2.0 / Audio.SR_SALIDA

            puestos.add(Puesto(nombre, tema.titulo, posicion, largo,
                primero = i == 0, ultimo = i == temas.lastIndex))

            // El siguiente arranca ANTES de que termine este: ese solape
            // es el cruce. Sin eso quedarian pegados y sin transicion.
            posicion += largo - enganchado.cruce
        }

        val bpms = temas.map { it.bpm }.filter { it > 0 }.sorted()
        val mediana = if (bpms.isEmpty()) 120.0 else bpms[bpms.size / 2]

        File(carpeta, "${enganchado.nombre}.rpp")
            .writeText(proyecto(mediana, puestos, enganchado.cruce))

        empaquetando()
        comprimir(carpeta, File(almacen.exportes, "${enganchado.nombre}.zip"))
    }

    private fun tramoEstereo(tema: Tema, cruce: Double): FloatArray {
        val archivo = File(tema.archivo ?: return FloatArray(0))
        if (!archivo.exists()) return FloatArray(0)
        val hastaSeg = minOf(tema.fin + cruce, tema.duracion)
        val pcm = Audio.decodificarTramo(archivo, tema.inicio, hastaSeg)
            ?: return FloatArray(0)

        val desde = 0
        val hasta = pcm.muestras.size / pcm.canales
        if (hasta <= desde) return FloatArray(0)

        val n = hasta - desde
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val base = (desde + i) * pcm.canales
            val izq = pcm.muestras[base]
            out[i * 2] = izq
            out[i * 2 + 1] = if (pcm.canales > 1) pcm.muestras[base + 1] else izq
        }
        return out
    }

    /**
     * El .rpp es texto plano, por eso se puede escribir desde aca.
     *
     * Los tramos alternan entre dos pistas (A y B) para que los solapes
     * queden visibles y editables por separado, mas una pista vacia para
     * el beat que quieras ponerle encima.
     */
    private fun proyecto(bpm: Double, puestos: List<Puesto>, cruce: Double): String {
        val lineas = mutableListOf(
            """<REAPER_PROJECT 0.1 "7.0" 0""",
            "  TEMPO %.2f 4 4".format(bpm),
        )

        for ((nombre, resto) in listOf("A" to 0, "B" to 1)) {
            lineas += "  <TRACK"
            lineas += """    NAME "$nombre""""
            puestos.forEachIndexed { i, p ->
                if (i % 2 == resto) lineas += item(p, cruce)
            }
            lineas += "  >"
        }

        lineas += listOf("  <TRACK", """    NAME "BEAT"""", "  >", ">")
        return lineas.joinToString("\n") + "\n"
    }

    private fun item(p: Puesto, cruce: Double): List<String> {
        val entra = if (p.primero) 0.0 else cruce
        val sale = if (p.ultimo) 0.0 else cruce
        val titulo = p.titulo.replace('"', '\'')
        return listOf(
            "    <ITEM",
            "      POSITION %.6f".format(p.posicion),
            "      LENGTH %.6f".format(p.largo),
            "      SOFFS 0",
            "      FADEIN 1 %.6f 0 1 0 0 0".format(entra),
            "      FADEOUT 1 %.6f 0 1 0 0 0".format(sale),
            """      NAME "$titulo"""",
            "      <SOURCE WAVE",
            """        FILE "${p.archivo}"""",
            "      >",
            "    >",
        )
    }

    /**
     * Empaqueta SIN comprimir.
     *
     * Comprimir WAV no rinde: medido, 146 MB quedaron en 138 MB (un 6%), y
     * ese 6% le costaba al celular un buen rato de CPU al final del export.
     * El audio PCM se parece demasiado a ruido para un compresor generico.
     * Guardado tal cual, el ZIP pesa un poco mas pero se arma casi al toque.
     */
    private fun comprimir(carpeta: File, destino: File): File {
        ZipOutputStream(destino.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            carpeta.listFiles()?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        carpeta.deleteRecursively()   // el ZIP ya lo tiene todo
        return destino
    }
}
