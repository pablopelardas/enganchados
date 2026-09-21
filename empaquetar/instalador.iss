; Instalador de Windows para Enganchados (Inno Setup 6.3+).
; Lo llama construir.py pasandole Version, Origen, Salida e Icono con /D.
;
; Se instala en la carpeta del usuario, sin pedir administrador: asi quien lo
; instala no se topa con un cartel pidiendo una contrasena que quizas no tiene.

#ifndef Version
  #define Version "0.0.0"
#endif

[Setup]
; Identifica la app ante Windows para actualizar encima. NUNCA cambiarlo:
; si cambia, la version nueva se instala como una app distinta.
AppId={{1D6F6CBE-1FB2-4AA1-A24E-9F24BFBCF72B}
AppName=Enganchados
AppVersion={#Version}
AppPublisher=Pablo Pelardas
AppPublisherURL=https://github.com/pablopelardas/enganchados
DefaultDirName={localappdata}\Programs\Enganchados
DefaultGroupName=Enganchados
DisableProgramGroupPage=yes
; Donde va el PROGRAMA no le importa a nadie; donde van SUS cosas si,
; y esa es la pagina que se muestra (ver [Code]).
DisableDirPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#Salida}
OutputBaseFilename=Enganchados-Windows-Setup
SetupIconFile={#Icono}
UninstallDisplayIcon={app}\Enganchados.exe
UninstallDisplayName=Enganchados
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "escritorio"; Description: "Crear un acceso directo en el escritorio"

[Files]
Source: "{#Origen}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\Enganchados"; Filename: "{app}\Enganchados.exe"
Name: "{autodesktop}\Enganchados"; Filename: "{app}\Enganchados.exe"; Tasks: escritorio

[Run]
Filename: "{app}\Enganchados.exe"; Description: "Abrir Enganchados"; Flags: nowait postinstall skipifsilent

; Al desinstalar NO se tocan los enganchados en Documentos\Enganchados:
; son del usuario, no de la app.

[Code]
// Pagina para elegir donde se guardan los enganchados. La eleccion se
// escribe en {userappdata}\Enganchados\config.json, que es donde la app la
// lee al arrancar (rutas.py). Despues se puede cambiar desde el menu.
var
  PaginaDatos: TInputDirWizardPage;

function ArchivoConfig: String;
begin
  Result := ExpandConstant('{userappdata}\Enganchados\config.json');
end;

procedure InitializeWizard;
begin
  PaginaDatos := CreateInputDirPage(wpSelectDir,
    'Dónde guardar tus enganchados',
    'Los temas bajados y las mezclas pueden ocupar bastante lugar.',
    'Elegí la carpeta donde se van a guardar. Después la podés cambiar desde el menú de la app.',
    False, '');
  PaginaDatos.Add('');
  PaginaDatos.Values[0] := ExpandConstant('{userdocs}\Enganchados');
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  // Al actualizar ya hay una eleccion: se respeta y no se vuelve a preguntar.
  Result := (PageID = PaginaDatos.ID) and FileExists(ArchivoConfig);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Ruta: String;
begin
  if (CurStep = ssPostInstall) and not FileExists(ArchivoConfig) then
  begin
    Ruta := PaginaDatos.Values[0];
    StringChangeEx(Ruta, '\', '\\', True);   // JSON: la barra se escapa
    ForceDirectories(ExtractFileDir(ArchivoConfig));
    SaveStringToFile(ArchivoConfig, '{"datos": "' + Ruta + '"}', False);
  end;
end;
