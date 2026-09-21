"""
Punto de entrada de la app de escritorio empaquetada.

Hace dos cosas segun como la llamen:

    Enganchados                      abre la ventana de la app
    Enganchados --tarea analizar ... corre una tarea y termina

La segunda existe porque adentro de la app no hay un Python suelto para
correr analizar.py o renderizar.py: el propio ejecutable atiende esas
tareas, y el server lo relanza a si mismo (ver servidor.lanzar).
"""
import os
import socket
import sys
import threading
import time


def asegurar_salida() -> None:
    """Una app de ventana en Windows arranca SIN consola: sys.stdout y
    sys.stderr son None, y el primer print revienta. uvicorn ademas le
    pregunta isatty() a stdout al configurar el log, y se cae ahi mismo.

    En una tarea, el server le pasa una tuberia: se usa esa. En la app, lo
    que se imprima va a un archivo de registro, util si algo falla.
    """
    if sys.stdout is not None and sys.stderr is not None:
        return
    try:
        # tarea lanzada por el server: fd 1 es la tuberia que lee el SSE
        salida = os.fdopen(1, "w", encoding="utf-8", errors="replace", buffering=1)
    except OSError:
        from rutas import DATOS
        DATOS.mkdir(parents=True, exist_ok=True)
        salida = open(DATOS / "registro.txt", "a", encoding="utf-8", buffering=1)
    sys.stdout = sys.stdout or salida
    sys.stderr = sys.stderr or salida


def correr_tarea(nombre: str, argv: list[str]) -> int:
    import rutas
    rutas.preparar_herramientas()
    if nombre == "analizar":
        import analizar
        analizar.main(argv)
    elif nombre == "renderizar":
        import renderizar
        renderizar.main(argv)
    else:
        print(f"Tarea desconocida: {nombre}")
        return 2
    return 0


def puerto_libre() -> int:
    """Un puerto que nadie use. Fijo (8000) chocaria con cualquier otra cosa
    que corra en la compu, y la app no abriria sin explicar por que."""
    if os.environ.get("ENGANCHADOS_PUERTO"):          # para pruebas
        return int(os.environ["ENGANCHADOS_PUERTO"])
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def esperar_puerto(puerto: int, limite: float = 20.0) -> bool:
    fin = time.time() + limite
    while time.time() < fin:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", puerto)) == 0:
                return True
        time.sleep(0.1)
    return False


def actualizar_ytdlp() -> None:
    """YouTube cambia seguido y el yt-dlp que viene con la app envejece.
    Se actualiza solo al abrir, en segundo plano, igual que en el celular."""
    import subprocess
    from rutas import SIN_VENTANA, herramienta
    try:
        subprocess.run([herramienta("yt-dlp"), "-U"], capture_output=True,
                       timeout=180, **SIN_VENTANA)
    except Exception as e:                            # sin internet, etc.
        print(f"No pude actualizar yt-dlp: {e}")


def abrir_app() -> None:
    import rutas
    rutas.DATOS.mkdir(parents=True, exist_ok=True)
    rutas.preparar_herramientas()
    threading.Thread(target=actualizar_ytdlp, daemon=True).start()

    import uvicorn
    import servidor

    puerto = puerto_libre()
    config = uvicorn.Config(servidor.app, host="127.0.0.1", port=puerto,
                            log_level="warning", access_log=False)
    server = uvicorn.Server(config)
    hilo = threading.Thread(target=server.run, daemon=True)
    hilo.start()

    url = f"http://127.0.0.1:{puerto}"
    if not esperar_puerto(puerto):
        print("El servidor no arranco a tiempo")
        return

    reiniciar = False
    try:
        import webview
        # Descargas y links a YouTube: sin esto la ventana ignora los botones
        # de descarga y abre YouTube adentro en vez de en el navegador.
        webview.settings["ALLOW_DOWNLOADS"] = True
        webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = True
        ventana = webview.create_window("Enganchados", url, width=560, height=900,
                                        min_size=(380, 600), background_color="#14161A")

        def elegir_carpeta():
            # el selector de carpetas del propio sistema
            carpeta = getattr(getattr(webview, "FileDialog", None), "FOLDER", None) \
                or webview.FOLDER_DIALOG
            r = ventana.create_file_dialog(carpeta, directory=str(rutas.DATOS))
            return r[0] if r else None

        def pedir_reinicio():
            nonlocal reiniciar
            reiniciar = True
            ventana.destroy()

        servidor.elegir_carpeta = elegir_carpeta
        servidor.pedir_reinicio = pedir_reinicio

        # private_mode=False: si no, la ventana olvida todo al cerrarse,
        # incluido el ultimo enganchado abierto. El almacenamiento de la
        # ventana va a la carpeta de configuracion y no a la de datos: si
        # cambias de carpeta, no pierde el ultimo enganchado abierto.
        webview.start(private_mode=False,
                      storage_path=str(rutas.CONFIG.parent / "ventana"))
    except Exception as e:
        # Sin motor de ventana (p. ej. un Windows sin WebView2): se usa el
        # navegador y una ventanita para poder cerrar la app.
        print(f"Sin ventana propia ({e}); abro el navegador")
        abrir_en_navegador(url)
    finally:
        server.should_exit = True
        hilo.join(timeout=5)

    if reiniciar:
        # Todas las rutas se fijan al arrancar: la carpeta nueva se aplica
        # con una instancia nueva de la app.
        import subprocess
        subprocess.Popen([sys.executable], close_fds=True)


def abrir_en_navegador(url: str) -> None:
    import tkinter as tk
    import webbrowser
    webbrowser.open(url)
    raiz = tk.Tk()
    raiz.title("Enganchados")
    raiz.geometry("360x120")
    tk.Label(raiz, text="Enganchados está abierto en tu navegador.\n"
                        "Cerrá esta ventana para salir.", pady=20).pack()
    tk.Button(raiz, text="Abrir de nuevo", command=lambda: webbrowser.open(url)).pack()
    raiz.mainloop()


def main() -> int:
    asegurar_salida()
    if len(sys.argv) >= 3 and sys.argv[1] == "--tarea":
        return correr_tarea(sys.argv[2], sys.argv[3:])
    abrir_app()
    return 0


if __name__ == "__main__":
    # En macOS, multiprocessing y algunas librerias relanzan el ejecutable;
    # PyInstaller necesita esto para no abrir una segunda ventana.
    import multiprocessing
    multiprocessing.freeze_support()
    sys.exit(main())
