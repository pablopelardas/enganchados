#!/usr/bin/env bash
# Doble clic aca en macOS (o ./Enganchados.command en Linux) y listo.
# Si falta algo, lo instala solo la primera vez.
cd "$(dirname "$0")"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

if [ ! -x .venv/bin/python ]; then
  printf '\n  Primera vez: instalando lo que falta. Esto tarda unos minutos.\n\n'
  if ! ./instalar.sh; then
    printf '\n  La instalacion fallo. Lee los mensajes de arriba.\n'
    read -r -p '  Enter para cerrar...' _
    exit 1
  fi
fi

# el navegador se abre un instante despues, cuando el server ya escucha
( sleep 2
  url=http://127.0.0.1:8000
  if command -v open >/dev/null; then open "$url"; else xdg-open "$url" >/dev/null 2>&1; fi
) &

# Para usarlo tambien desde el celular, agregale --lan a la linea de abajo
exec .venv/bin/python enganchado/servidor.py
