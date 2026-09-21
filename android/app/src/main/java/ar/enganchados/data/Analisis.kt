package ar.enganchados.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mide el tema y PROPONE un tramo. No decide nada definitivo: lo que sale
 * de aca es una sugerencia que el usuario ajusta a oreja.
 *
 * Es el port del analizar.py de la version de escritorio, sin librosa.
 */
object Analisis {

    const val HOP = 512          // ~43 cuadros por segundo a 22050 Hz
    const val COMPAS = 4         // 4/4 sirve para todos estos generos

    private const val BPM_MIN = 82.0
    private const val BPM_MAX = 164.0

    class Resultado(
        val bpm: Double,
        val bpmCrudo: Double,
        val duracion: Double,
        val compases: DoubleArray,
        val envolvente: FloatArray,   // para dibujar la onda
        val inicio: Double,
        val fin: Double,
    )

    /**
     * Analiza a partir de la envolvente de energia, que es todo lo que hace
     * falta. [rms] tiene un valor por cuadro de HOP muestras a SR_ANALISIS
     * (lo arma Audio.envolvente en streaming, sin guardar muestras).
     */
    fun analizarEnvolvente(rms: FloatArray, duracion: Double, duracionTramo: Double): Resultado {
        val sr = Audio.SR_ANALISIS
        val onsets = fuerzaDeOnset(rms)

        val bpmCrudo = estimarBpm(onsets, sr)
        val bpm = plegarBpm(bpmCrudo)
        val compases = grillaDeCompases(onsets, bpm, sr, duracion)

        val (ini, fin) = proponerTramo(rms, sr, compases, duracion, duracionTramo)

        return Resultado(bpm, bpmCrudo, duracion, compases, rms, ini, fin)
    }

    /**
     * Corrige errores de octava del detector.
     *
     * Los estimadores de tempo eligen mal el nivel metrico muy seguido:
     * reportan mitad o doble de tiempo. Ningun tema bailable vive fuera
     * de 82-164 BPM, asi que duplicamos o dividimos hasta caer adentro.
     */
    fun plegarBpm(bpm: Double, minimo: Double = BPM_MIN, maximo: Double = BPM_MAX): Double {
        if (bpm <= 0) return 0.0
        var v = bpm
        while (v < minimo) v *= 2
        while (v > maximo) v /= 2
        return v
    }

    /** Energia por cuadro. Es la base de todo lo demas. */
    fun envolventeRms(x: FloatArray): FloatArray {
        val n = x.size / HOP
        if (n <= 0) return FloatArray(0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            var suma = 0.0
            val desde = i * HOP
            val hasta = minOf(desde + HOP, x.size)
            for (j in desde until hasta) suma += x[j].toDouble() * x[j]
            out[i] = sqrt(suma / max(1, hasta - desde)).toFloat()
        }
        return out
    }

    /**
     * Subidas de energia entre cuadros consecutivos.
     *
     * Es un sustituto del flujo espectral: mas pobre, pero para cumbia,
     * axe y carnaval —donde el golpe percusivo domina— alcanza, y evita
     * meter una FFT completa.
     */
    private fun fuerzaDeOnset(rms: FloatArray): FloatArray {
        if (rms.size < 2) return FloatArray(0)
        val out = FloatArray(rms.size - 1)
        for (i in 1 until rms.size) {
            out[i - 1] = max(0f, rms[i] - rms[i - 1])   // solo las subidas
        }
        return out
    }

    /** BPM por autocorrelacion de la curva de onsets. */
    private fun estimarBpm(onsets: FloatArray, sr: Int): Double {
        if (onsets.size < 32) return 120.0

        val porCuadro = sr.toDouble() / HOP           // cuadros por segundo
        val minLag = (porCuadro * 60.0 / 200.0).roundToInt().coerceAtLeast(1)
        val maxLag = (porCuadro * 60.0 / 50.0).roundToInt().coerceAtMost(onsets.size / 2)
        if (maxLag <= minLag) return 120.0

        val media = onsets.average()
        val centrado = DoubleArray(onsets.size) { onsets[it] - media }

        var mejorLag = minLag
        var mejorPuntaje = -Double.MAX_VALUE
        for (lag in minLag..maxLag) {
            var suma = 0.0
            for (i in 0 until centrado.size - lag) suma += centrado[i] * centrado[i + lag]
            val puntaje = suma / (centrado.size - lag)
            if (puntaje > mejorPuntaje) {
                mejorPuntaje = puntaje
                mejorLag = lag
            }
        }
        return 60.0 * porCuadro / mejorLag
    }

    /**
     * Arranques de compas.
     *
     * Con el BPM ya sabemos cada cuanto cae un beat; falta la FASE. La
     * buscamos probando corrimientos y quedandonos con el que mas energia
     * de onset acumula, que es donde estan los golpes de verdad.
     */
    private fun grillaDeCompases(
        onsets: FloatArray, bpm: Double, sr: Int, duracion: Double,
    ): DoubleArray {
        if (bpm <= 0 || onsets.isEmpty()) return doubleArrayOf(0.0)

        val porCuadro = sr.toDouble() / HOP
        val cuadrosPorBeat = 60.0 / bpm * porCuadro
        if (cuadrosPorBeat < 1) return doubleArrayOf(0.0)

        var mejorFase = 0
        var mejorPuntaje = -1.0
        val pasos = cuadrosPorBeat.roundToInt().coerceAtLeast(1)
        for (fase in 0 until pasos) {
            var suma = 0.0
            var k = fase.toDouble()
            while (k < onsets.size) {
                suma += onsets[k.toInt()]
                k += cuadrosPorBeat
            }
            if (suma > mejorPuntaje) {
                mejorPuntaje = suma
                mejorFase = fase
            }
        }

        val segPorCompas = 60.0 / bpm * COMPAS
        val desde = mejorFase / porCuadro
        val salida = ArrayList<Double>()
        var t = desde
        while (t < duracion) {
            salida.add(t)
            t += segPorCompas
        }
        return if (salida.isEmpty()) doubleArrayOf(0.0) else salida.toDoubleArray()
    }

    /**
     * Elige el tramo de mayor energia sostenida y lo pega al compas.
     *
     * El estribillo casi siempre es la parte mas energica del tema, asi
     * que esto ademas esquiva solo las intros habladas y los fade-in
     * largos de los videos oficiales.
     */
    private fun proponerTramo(
        rms: FloatArray, sr: Int, compases: DoubleArray,
        duracion: Double, largo: Double,
    ): Pair<Double, Double> {
        if (rms.isEmpty()) return 0.0 to minOf(largo, duracion)

        val porCuadro = sr.toDouble() / HOP
        val cuadrosVentana = (largo * porCuadro).toInt().coerceAtLeast(1)

        val candidatos = compases.filter { it + largo <= duracion }
        if (candidatos.isEmpty()) return 0.0 to minOf(largo, duracion)

        var mejor = candidatos.first()
        var mejorEnergia = -1.0
        for (inicio in candidatos) {
            val desde = (inicio * porCuadro).toInt()
            val hasta = minOf(desde + cuadrosVentana, rms.size)
            if (hasta <= desde) continue
            var suma = 0.0
            for (i in desde until hasta) suma += rms[i]
            val promedio = suma / (hasta - desde)
            if (promedio > mejorEnergia) {
                mejorEnergia = promedio
                mejor = inicio
            }
        }

        // el corte tambien cae en compas, para que la transicion no quede coja
        val fin = compases.firstOrNull { it >= mejor + largo } ?: (mejor + largo)
        return redondear(mejor) to redondear(minOf(fin, duracion))
    }

    /** Ajusta el largo a una cantidad entera de compases, fijando el arranque. */
    fun pegarAlCompas(inicio: Double, fin: Double, bpm: Double): Pair<Double, Double> {
        if (bpm <= 0) return inicio to fin
        val compas = 60.0 / bpm * COMPAS
        val cantidad = max(1.0, ((fin - inicio) / compas).roundToInt().toDouble())
        return inicio to redondear(inicio + cantidad * compas)
    }

    fun compasesEn(inicio: Double, fin: Double, bpm: Double): Int {
        if (bpm <= 0) return 0
        return ((fin - inicio) / (60.0 / bpm * COMPAS)).roundToInt()
    }

    private fun redondear(v: Double) = (v * 100).roundToInt() / 100.0

    /** Reduce la envolvente a N columnas para dibujarla en pantalla. */
    fun paraDibujar(envolvente: FloatArray, columnas: Int): FloatArray {
        if (envolvente.isEmpty() || columnas <= 0) return FloatArray(0)
        val out = FloatArray(columnas)
        val porColumna = envolvente.size.toDouble() / columnas
        var pico = 0f
        for (i in 0 until columnas) {
            val desde = (i * porColumna).toInt()
            val hasta = minOf(((i + 1) * porColumna).toInt(), envolvente.size)
            var m = 0f
            for (j in desde until max(desde + 1, hasta)) {
                if (j < envolvente.size) m = max(m, abs(envolvente[j]))
            }
            out[i] = m
            pico = max(pico, m)
        }
        if (pico > 0) for (i in out.indices) out[i] /= pico
        return out
    }
}
