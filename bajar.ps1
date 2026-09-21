<#
  Baja los enganchados definidos en sets\*.txt

  Uso:
    .\bajar.ps1                        -> baja TODOS los sets
    .\bajar.ps1 01-carnaval-carioca    -> baja solo ese set

  Cada tema se numera segun su posicion en la lista, asi el orden
  del enganchado se respeta. Re-ejecutar el script NO vuelve a bajar
  lo que ya esta (usa .archivo.txt), solo completa lo que falto.
#>
param([string]$Set = "*")

$raiz   = $PSScriptRoot
$config = Join-Path $raiz "yt-dlp.conf"
$listas = @(Get-ChildItem -Path (Join-Path $raiz "sets") -Filter "$Set.txt" -ErrorAction SilentlyContinue)

if ($listas.Count -eq 0) {
    Write-Host "No hay ninguna lista en sets\ que coincida con '$Set'" -ForegroundColor Red
    exit 1
}

foreach ($lista in $listas) {
    $nombre  = $lista.BaseName
    $destino = Join-Path $raiz "musica\$nombre"
    New-Item -ItemType Directory -Force -Path $destino | Out-Null

    $archivo = Join-Path $destino ".archivo.txt"
    $reporte = Join-Path $destino "_reporte.txt"
    # Mapa posicion -> ID de video. El nombre del archivo dice QUE es, no
    # DONDE va: el orden del enganchado vive aca y en la receta.
    $orden   = Join-Path $destino "_orden.txt"

    $temas = @(Get-Content $lista.FullName |
               Where-Object { $_.Trim() -and -not $_.Trim().StartsWith("#") })

    Write-Host ""
    Write-Host "==> $nombre  ($($temas.Count) temas)" -ForegroundColor Cyan

    $i = 0
    $fallos = @()

    foreach ($tema in $temas) {
        $i++
        $n = "{0:d2}" -f $i
        Write-Host "  [$n/$($temas.Count)] $($tema.Trim())" -ForegroundColor DarkGray

        yt-dlp $tema.Trim() `
            --config-locations $config `
            --paths $destino `
            --output "[%(id)s] %(title)s.%(ext)s" `
            --download-archive $archivo `
            --print-to-file "$n|%(id)s" $orden `
            --print-to-file "$n | %(title)s | %(duration_string)s | %(webpage_url)s" $reporte `
            --quiet --no-warnings --progress

        if ($LASTEXITCODE -ne 0) { $fallos += "$n  $($tema.Trim())" }
    }

    Write-Host ""
    if ($fallos.Count -gt 0) {
        Write-Host "  Fallaron $($fallos.Count) tema(s) en ${nombre}:" -ForegroundColor Yellow
        $fallos | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
        Write-Host "  Arreglalos poniendo la URL exacta de YouTube en sets\$nombre.txt y volve a correr." -ForegroundColor Yellow
    } else {
        Write-Host "  $nombre completo." -ForegroundColor Green
    }
    Write-Host "  Verifica que bajo lo correcto en: musica\$nombre\_reporte.txt" -ForegroundColor Green
}
