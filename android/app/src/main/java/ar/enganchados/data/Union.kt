package ar.enganchados.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Como se pegan los tramos, sin nada de Android: se prueba sola.
 *
 * Los arreglos son PCM intercalado (L R L R ...). Todo corte cae en un
 * cuadro entero: cortar a mitad de un cuadro corre los canales una muestra
 * y el resto del tema suena con izquierda y derecha cambiadas.
 */
object Union {

    /**
     * Va pegando tramos con un cruce entre cada uno y escribe a medida que
     * avanza: solo retiene la cola del ultimo tramo, nunca la mezcla entera.
     *
     * Anota en [cruces] el cuadro donde REALMENTE empieza cada cruce. Antes
     * esas posiciones se calculaban aparte desde la receta, y en cuanto un
     * tramo salia mas corto de lo previsto (llegaba al final del tema) las
     * flechas del reproductor caian cada vez mas lejos de la transicion.
     */
    class Cosedor(
        private val cruce: Int,
        private val canales: Int,
        private val escribir: (x: FloatArray, desde: Int, hasta: Int) -> Unit,
    ) {
        private var cola: FloatArray? = null
        private var escritas = 0L

        val cruces = mutableListOf<Long>()

        fun agregar(tramo: FloatArray) {
            if (tramo.isEmpty()) return
            // Nunca mas de la mitad del tramo: con uno muy corto, el cruce se
            // comeria el tramo entero.
            val c = enCuadros(minOf(cruce, tramo.size / 2))
            val previo = cola

            if (previo == null) {
                emitir(tramo, 0, tramo.size - c)
            } else {
                val n = minOf(previo.size, c)
                cruces += escritas / canales
                emitir(fundir(previo, tramo, n), 0, n)
                emitir(tramo, n, tramo.size - c)
            }
            cola = tramo.copyOfRange(tramo.size - c, tramo.size)
        }

        fun cerrar() {
            cola?.let { emitir(it, 0, it.size) }
            cola = null
        }

        private fun emitir(x: FloatArray, desde: Int, hasta: Int) {
            if (hasta <= desde) return
            escribir(x, desde, hasta)
            escritas += hasta - desde
        }

        private fun enCuadros(muestras: Int) = muestras - muestras % canales

        /**
         * Fundido de igual potencia. El lineal (1-t, t) deja un pozo de ~3 dB
         * en el medio del cruce, porque dos temas distintos no suman en fase:
         * justo en la union se sentia que "se caia" la musica.
         */
        private fun fundir(sale: FloatArray, entra: FloatArray, n: Int): FloatArray {
            val cuadros = n / canales
            val out = FloatArray(n)
            for (k in 0 until cuadros) {
                val t = (k + 0.5) / cuadros * (PI / 2)
                val a = cos(t).toFloat()
                val b = sin(t).toFloat()
                for (ch in 0 until canales) {
                    val i = k * canales + ch
                    out[i] = sale[i] * a + entra[i] * b
                }
            }
            return out
        }
    }

    /** Por debajo de esto (~ -44 dBFS RMS) se considera silencio. */
    private const val UMBRAL = 0.006f
    private const val VENTANA_S = 0.02
    private const val MARGEN_S = 0.05

    /**
     * Saca el silencio del principio y del final del tramo.
     *
     * Muchos uploads de YouTube terminan con segundos mudos y otros arrancan
     * igual. Si el tramo llega hasta ahi, el cruce funde silencio con
     * silencio y en el medio del enganchado queda un hueco. Se deja un margen
     * chico para no morder el ataque de la primera nota.
     */
    fun recortarSilencio(x: FloatArray, canales: Int, sr: Int): FloatArray {
        val cuadros = x.size / canales
        val ventana = maxOf(1, (sr * VENTANA_S).toInt())
        val margen = (sr * MARGEN_S).toInt()
        val ventanas = (cuadros + ventana - 1) / ventana

        fun suena(v: Int): Boolean {
            val desde = v * ventana
            val hasta = minOf(cuadros, desde + ventana)
            var suma = 0.0
            for (i in desde * canales until hasta * canales) suma += x[i].toDouble() * x[i]
            return sqrt(suma / ((hasta - desde) * canales)) > UMBRAL
        }

        val primera = (0 until ventanas).firstOrNull(::suena) ?: return x
        val ultima = (ventanas - 1 downTo 0).first(::suena)

        val desde = maxOf(0, primera * ventana - margen)
        val hasta = minOf(cuadros, (ultima + 1) * ventana + margen)
        if (desde == 0 && hasta == cuadros) return x
        return x.copyOfRange(desde * canales, hasta * canales)
    }
}
