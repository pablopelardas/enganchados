@echo off
REM Doble clic aca y listo. Si falta algo, lo instala solo la primera vez.
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo.
    echo   Primera vez: instalando lo que falta. Esto tarda unos minutos.
    echo.
    powershell -NoProfile -ExecutionPolicy Bypass -File "instalar.ps1"
    if errorlevel 1 (
        echo.
        echo   La instalacion fallo. Leé los mensajes de arriba.
        pause
        exit /b 1
    )
)

REM Para usarlo tambien desde el celular, agregale --lan a la linea de abajo
start "" http://127.0.0.1:8000
".venv\Scripts\python.exe" "enganchado\servidor.py"

pause
