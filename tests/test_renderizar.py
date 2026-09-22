"""Tests de lo que se puede probar sin ffmpeg: recorte de silencio y cruces.

    python -m unittest discover tests
"""
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "enganchado"))

from renderizar import cruce_efectivo, cruces_de, limites_sin_silencio  # noqa: E402

SR = 1000


def estereo(*partes):
    """Concatena (segundos, nivel) en un arreglo (cuadros, 2) de int16."""
    bloques = [np.full((int(s * SR), 2), int(n * 32767), dtype=np.int16) for s, n in partes]
    return np.concatenate(bloques)


class Cruces(unittest.TestCase):

    def test_cada_cruce_empieza_donde_termina_lo_propio_del_anterior(self):
        # igual que en Android: 100 escribe 90 y cruza; 60 suma 50 mas
        self.assertEqual(cruces_de([100, 60, 80], 10), [90, 140])

    def test_con_tramos_cortos_el_cruce_no_supera_la_mitad(self):
        self.assertEqual(cruce_efectivo([40, 100], 50), 20)
        self.assertEqual(cruces_de([40, 100], 50), [20])

    def test_sin_tramos_suficientes_no_hay_cruces(self):
        self.assertEqual(cruces_de([100], 10), [])


class Silencio(unittest.TestCase):

    def test_recorta_el_silencio_del_principio_y_del_final(self):
        x = estereo((1, 0), (2, 0.5), (1.5, 0))
        desde, hasta = limites_sin_silencio(x, SR)
        # queda el sonido (2 s) mas un margen chico de cada lado
        self.assertTrue(900 <= desde <= 1000, desde)
        self.assertTrue(3000 <= hasta <= 3100, hasta)

    def test_si_no_hay_silencio_no_toca(self):
        x = estereo((3, 0.5))
        self.assertEqual(limites_sin_silencio(x, SR), (0, len(x)))

    def test_si_todo_es_silencio_lo_deja_como_esta(self):
        x = estereo((3, 0))
        self.assertEqual(limites_sin_silencio(x, SR), (0, len(x)))

    def test_un_ruido_de_fondo_bajo_cuenta_como_silencio(self):
        x = estereo((1, 0.002), (2, 0.5), (1, 0.002))
        desde, hasta = limites_sin_silencio(x, SR)
        self.assertGreater(desde, 800)
        self.assertLess(hasta, 3200)


if __name__ == "__main__":
    unittest.main()
