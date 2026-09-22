package ar.enganchados.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnionTest {

    private val canales = 2

    /** Un tramo estereo de [cuadros] cuadros con el mismo valor en todo. */
    private fun tramo(cuadros: Int, valor: Float = 0.5f) = FloatArray(cuadros * canales) { valor }

    private fun coser(cruce: Int, vararg tramos: FloatArray): Pair<FloatArray, List<Long>> {
        val salida = ArrayList<Float>()
        val cosedor = Union.Cosedor(cruce, canales) { x, desde, hasta ->
            for (i in desde until hasta) salida += x[i]
        }
        tramos.forEach(cosedor::agregar)
        cosedor.cerrar()
        return salida.toFloatArray() to cosedor.cruces
    }

    // ------------------------------------------------------------ coser

    @Test
    fun cadaCruceSuperponeLosTramosYAcortaLaMezcla() {
        val (mezcla, _) = coser(10 * canales, tramo(100), tramo(100), tramo(100))
        // 3 tramos de 100 cuadros, 2 cruces de 10 superpuestos
        assertEquals((300 - 2 * 10) * canales, mezcla.size)
    }

    @Test
    fun anotaDondeEmpiezaCadaCruceDeVerdad() {
        val (_, cruces) = coser(10 * canales, tramo(100), tramo(60), tramo(80))
        // el 1ro escribe 90 y el cruce empieza ahi; el 2do suma 60 - 10 = 50
        assertEquals(listOf(90L, 140L), cruces)
    }

    @Test
    fun conTramosCortosElCruceNoSeComeMasDeLaMitad() {
        val (mezcla, cruces) = coser(50 * canales, tramo(40), tramo(40))
        // cruce recortado a 20 cuadros (la mitad de 40)
        assertEquals((80 - 20) * canales, mezcla.size)
        assertEquals(listOf(20L), cruces)
    }

    @Test
    fun conCantidadImparDeCuadrosNoSeCruzanLosCanales() {
        // izquierda 1, derecha -1: si el cruce se corre una muestra, se mezclan
        val a = FloatArray(41 * canales) { if (it % 2 == 0) 1f else -1f }
        val b = FloatArray(41 * canales) { if (it % 2 == 0) 1f else -1f }
        val (mezcla, _) = coser(100 * canales, a, b)
        mezcla.forEachIndexed { i, v ->
            if (i % 2 == 0) assertTrue("izq en $i = $v", v > 0) else assertTrue("der en $i = $v", v < 0)
        }
    }

    @Test
    fun elCruceMantieneElVolumenEnElMedio() {
        // Dos tramos al mismo nivel: un fundido lineal baja ~3 dB en el medio
        // del cruce con musica real. El de igual potencia no hace ese pozo.
        val (mezcla, cruces) = coser(100 * canales, tramo(400), tramo(400))
        val medio = ((cruces[0] + 50) * canales).toInt()
        assertTrue("en el medio del cruce: ${mezcla[medio]}", mezcla[medio] > 0.6f)
    }

    // ---------------------------------------------------------- silencio

    @Test
    fun recortaElSilencioDelPrincipioYDelFinal() {
        val sr = 1000
        val sonido = tramo(2000)
        val x = FloatArray(1000 * canales) + sonido + FloatArray(1500 * canales)
        val y = Union.recortarSilencio(x, canales, sr)
        // queda el sonido mas un margen chico de cada lado, no los 2.5 s mudos
        val cuadros = y.size / canales
        assertTrue("quedaron $cuadros cuadros", cuadros in 2000..2200)
    }

    @Test
    fun siNoHaySilencioNoToca() {
        val x = tramo(3000)
        assertArrayEquals(x, Union.recortarSilencio(x, canales, 1000), 0f)
    }

    @Test
    fun siTodoEsSilencioLoDejaComoEsta() {
        val x = FloatArray(3000 * canales)
        assertEquals(x.size, Union.recortarSilencio(x, canales, 1000).size)
    }

    @Test
    fun elRecorteDejaCuadrosEnteros() {
        val x = FloatArray(333 * canales) + tramo(777) + FloatArray(555 * canales)
        assertEquals(0, Union.recortarSilencio(x, canales, 1000).size % canales)
    }
}
