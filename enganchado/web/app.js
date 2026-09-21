import WaveSurfer from 'https://cdn.jsdelivr.net/npm/wavesurfer.js@7/dist/wavesurfer.esm.js'
import RegionsPlugin from 'https://cdn.jsdelivr.net/npm/wavesurfer.js@7/dist/plugins/regions.esm.js'

// Misma logica que la app de Android. Los archivos del server son la fuente
// de verdad: esto solo los muestra y le pide al server que los cambie.

const $ = (s) => document.querySelector(s)

const estado = {
  sets: [],
  set: null,
  filas: [],        // una por tema de la lista, con su audio resuelto por ID
  receta: null,     // los temas ya analizados, con su tramo
  indice: 0,        // tema elegido en Tramos (posicion en la receta)
  trabajando: false,
  cruces: [],       // segundo donde arranca cada cruce dentro de la mezcla
}

// ================================================================ utiles

async function api (ruta, opciones = {}) {
  const res = await fetch(ruta, {
    headers: { 'Content-Type': 'application/json' },
    ...opciones,
  })
  if (!res.ok) {
    const d = await res.json().catch(() => ({}))
    const e = new Error(d.detail || `${res.status} en ${ruta}`)
    e.status = res.status
    throw e
  }
  return res.json()
}

const escapar = (t) => String(t ?? '').replace(/[&<>"]/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

const reloj = (s) => {
  s = Math.max(0, Math.floor(s || 0))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

const vistas = (n) => n >= 1e6 ? `${(n / 1e6).toFixed(1)}M`
  : n >= 1e3 ? `${Math.round(n / 1e3)}k` : String(n || '?')

const guardadoLocal = {
  leer: (k) => { try { return localStorage.getItem(k) } catch { return null } },
  escribir: (k, v) => { try { localStorage.setItem(k, v) } catch { /* sin storage */ } },
}

let temporizadorAviso
function avisar (texto) {
  const a = $('#aviso')
  a.textContent = texto
  a.classList.add('visible')
  clearTimeout(temporizadorAviso)
  temporizadorAviso = setTimeout(() => a.classList.remove('visible'), 3500)
}

/**
 * Confirmacion en dos toques. confirm() nativo no sirve: hay navegadores y
 * paneles embebidos que lo suprimen y devuelven false al instante, asi que
 * la accion parecia "no hacer nada".
 */
function pedirConfirmacion (boton, accion, texto = '¿Seguro?') {
  if (boton.dataset.armado === '1') {
    desarmar(boton)
    return accion()
  }
  document.querySelectorAll('.armado').forEach(desarmar)
  boton.dataset.original = boton.innerHTML
  boton.dataset.armado = '1'
  boton.textContent = texto
  boton.classList.add('armado')
  setTimeout(() => { if (boton.dataset.armado === '1') desarmar(boton) }, 3000)
}

function desarmar (b) {
  if (b.dataset.original) b.innerHTML = b.dataset.original
  delete b.dataset.armado
  b.classList.remove('armado')
}

// ====================================================== tareas largas (SSE)

function mostrarProgreso (f) {
  $('#progreso > div').style.width = `${Math.round(Math.max(0, Math.min(1, f)) * 100)}%`
}

function mostrarEstado (texto) {
  const e = $('#estado')
  e.hidden = !texto
  e.textContent = texto || ''
}

/**
 * Corre una tarea larga y muestra su avance arriba, como en el celular.
 * El detalle completo queda en un log que se abre tocando el estado.
 * El server manda SSE sobre un POST, asi que el stream se lee a mano:
 * EventSource solo sabe hacer GET.
 */
async function correrTarea (ruta, cuerpo, titulo) {
  if (estado.trabajando) return false
  estado.trabajando = true
  refrescarBotones()
  mostrarEstado(titulo)
  mostrarProgreso(0.03)
  $('#log-titulo').textContent = titulo
  $('#log').textContent = ''

  let codigo = 1
  try {
    const res = await fetch(ruta, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(cuerpo || {}),
    })
    const lector = res.body.getReader()
    const deco = new TextDecoder()
    let pendiente = ''

    for (;;) {
      const { done, value } = await lector.read()
      if (done) break
      pendiente += deco.decode(value, { stream: true })
      const bloques = pendiente.split('\n\n')
      pendiente = bloques.pop()

      for (const b of bloques) {
        const crudo = b.replace(/^data: /, '').trim()
        if (!crudo) continue
        const d = JSON.parse(crudo)
        if (d.linea !== undefined) {
          $('#log').textContent += d.linea + '\n'
          // "[03/24]" en cualquier linea = avance
          const m = /\[(\d+)\/(\d+)\]/.exec(d.linea)
          if (m) {
            mostrarProgreso(Number(m[1]) / Number(m[2]))
            mostrarEstado(`${titulo} ${Number(m[1])}/${Number(m[2])}`)
          }
        }
        if (d.fin) codigo = d.codigo
      }
    }
  } catch (err) {
    $('#log').textContent += `\n${err.message}\n`
  } finally {
    estado.trabajando = false
    mostrarProgreso(0)
    refrescarBotones()
  }

  if (codigo === 0) {
    mostrarEstado('')
  } else {
    mostrarEstado(`${titulo}: falló · tocá para ver el detalle`)
    avisar('Algo falló. Tocá el aviso de arriba para ver el detalle.')
  }
  return codigo === 0
}

$('#estado').onclick = () => $('#hoja-log').showModal()
$('#cerrar-log').onclick = () => $('#hoja-log').close()

// Los botones que disparan trabajo pesado se apagan mientras hay uno corriendo.
function refrescarBotones () {
  const t = estado.trabajando
  for (const id of ['#btn-bajar', '#btn-analizar', '#btn-armar', '#btn-stems',
    '#re-analizar', '#actualizar-ytdlp', '#lateral-ytdlp']) {
    const b = $(id)
    if (b) b.disabled = t || b.dataset.deshabilitado === '1'
  }
}

// ======================================================= hojas y pestanas

// Cerrar tocando afuera de la hoja, como en Android.
document.querySelectorAll('dialog.hoja').forEach((d) => {
  d.addEventListener('click', (e) => { if (e.target === d) d.close() })
})

let panelActual = 'temas'

function mostrarPanel (nombre) {
  panelActual = nombre
  document.querySelectorAll('#tabs button').forEach((b) =>
    b.classList.toggle('activa', b.dataset.panel === nombre))
  document.querySelectorAll('.panel').forEach((p) => p.classList.remove('activa'))
  $(`#p-${nombre}`).classList.add('activa')
  if (nombre !== 'tramos') pausarTramo()
  if (nombre === 'tramos') pintarTramos()
  if (nombre === 'armar') pintarArmar()
}

document.querySelectorAll('#tabs button').forEach((b) => {
  b.onclick = () => mostrarPanel(b.dataset.panel)
})

// ========================================================== enganchados

async function cargarSets () {
  estado.sets = await api('/api/sets')
  pintarListaSets()
}

function pintarListaSets () {
  const ul = $('#lista-sets')
  ul.innerHTML = ''
  for (const s of estado.sets) {
    const li = document.createElement('li')
    li.innerHTML = `
      <button class="abrir ${s.nombre === estado.set ? 'actual' : ''}">
        ${escapar(s.nombre)}
        <small>${s.descargados}/${s.en_lista} bajados${s.tiene_salida ? ' · armado' : ''}</small>
      </button>
      <button class="icono borrar" aria-label="Borrar">
        <svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>
      </button>`
    li.querySelector('.abrir').onclick = () => { $('#hoja-sets').close(); abrirSet(s.nombre) }
    // Doble toque: borrar se lleva lista, receta, audios y exportes.
    li.querySelector('.borrar').onclick = (e) =>
      pedirConfirmacion(e.currentTarget, () => borrarSet(s.nombre), 'Borrar todo')
    ul.appendChild(li)
  }

  // Barra lateral de escritorio: la misma lista, siempre a la vista.
  const lat = $('#lateral-sets')
  lat.innerHTML = ''
  for (const s of estado.sets) {
    const li = document.createElement('li')
    li.className = s.nombre === estado.set ? 'actual' : ''
    li.innerHTML = `
      <button class="abrir">
        <span>${escapar(s.nombre)}</span>
        <small>${s.descargados}/${s.en_lista} bajados${s.tiene_salida ? ' · armado' : ''}</small>
      </button>
      <button class="icono borrar" aria-label="Borrar">
        <svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>
      </button>`
    li.querySelector('.abrir').onclick = () => abrirSet(s.nombre)
    li.querySelector('.borrar').onclick = (e) =>
      pedirConfirmacion(e.currentTarget, () => borrarSet(s.nombre), 'Borrar todo')
    lat.appendChild(li)
  }

  const bl = $('#bienvenida-lista')
  bl.innerHTML = estado.sets.length ? '<p class="tenue chico">O seguí con uno:</p>' : ''
  for (const s of estado.sets) {
    const b = document.createElement('button')
    b.className = 'texto ancho'
    b.textContent = s.nombre
    b.onclick = () => abrirSet(s.nombre)
    bl.appendChild(b)
  }
}

async function abrirSet (nombre) {
  pausarTramo()
  pausarMezcla()
  estado.set = nombre
  estado.indice = 0
  guardadoLocal.escribir('enganchado', nombre)
  $('#titulo').textContent = nombre
  $('#tabs').hidden = false
  document.querySelectorAll('.panel').forEach((p) => p.classList.remove('activa'))
  await Promise.all([cargarFilas(), cargarReceta()])
  estado.cruces = calcularCruces()
  prepararReproductor()
  pintarListaSets()
  mostrarPanel(panelActual)
}

function mostrarBienvenida () {
  estado.set = null
  $('#titulo').textContent = 'Enganchados'
  $('#tabs').hidden = true
  document.querySelectorAll('.panel').forEach((p) => p.classList.remove('activa'))
  $('#bienvenida').classList.add('activa')
  pintarListaSets()
}

async function crearSet (nombre) {
  nombre = nombre.trim().replace(/\s+/g, '-').replace(/[^A-Za-z0-9_-]/g, '')
  if (!nombre) return avisar('Poné un nombre con letras o números')
  try {
    await api('/api/sets', { method: 'POST', body: JSON.stringify({ nombre }) })
    await cargarSets()
    panelActual = 'temas'
    await abrirSet(nombre)
  } catch (e) { avisar(e.message) }
}

async function borrarSet (nombre) {
  if (nombre === estado.set) { pausarMezcla(); pausarTramo() }
  const r = await api(`/api/sets/${nombre}`, { method: 'DELETE' })
  avisar(`Borrado ${nombre} (${r.borrados.length} elementos)`)
  await cargarSets()
  if (nombre === estado.set) {
    if (estado.sets.length) abrirSet(estado.sets[0].nombre)
    else mostrarBienvenida()
  }
}

$('#form-nuevo').onsubmit = (e) => { e.preventDefault(); crearSet($('#nombre-nuevo').value) }
$('#form-nuevo-hoja').onsubmit = (e) => {
  e.preventDefault()
  const n = $('#nombre-nuevo-hoja').value
  $('#nombre-nuevo-hoja').value = ''
  $('#hoja-sets').close()
  crearSet(n)
}
$('#form-nuevo-lateral').onsubmit = (e) => {
  e.preventDefault()
  const n = $('#nombre-nuevo-lateral').value
  $('#nombre-nuevo-lateral').value = ''
  crearSet(n)
}
$('#abrir-sets').onclick = () => { pintarListaSets(); $('#hoja-sets').showModal() }
$('#cerrar-sets').onclick = () => $('#hoja-sets').close()
async function actualizarYtdlp () {
  $('#hoja-sets').close()
  if (await correrTarea('/api/actualizar-ytdlp', {}, 'Actualizando yt-dlp')) {
    avisar('yt-dlp al día')
  }
}
$('#actualizar-ytdlp').onclick = actualizarYtdlp
$('#lateral-ytdlp').onclick = actualizarYtdlp

// ================================================================ temas

async function cargarFilas () {
  estado.filas = await api(`/api/sets/${estado.set}/filas`)
  pintarFilas()
}

function pintarFilas () {
  const faltan = estado.filas.filter((f) => !f.bajado).length
  const bajar = $('#btn-bajar')
  bajar.textContent = `Bajar (${faltan})`
  bajar.dataset.deshabilitado = faltan ? '0' : '1'
  refrescarBotones()

  const ol = $('#filas')
  ol.innerHTML = ''
  if (!estado.filas.length) {
    ol.innerHTML = '<li class="vacio">Sin temas todavía.<br>Tocá "+ Agregar" y buscá el primero.</li>'
    return
  }

  const ultimo = estado.filas.length
  for (const f of estado.filas) {
    const li = document.createElement('li')
    li.innerHTML = `
      <span class="fila-num">${String(f.pos).padStart(2, '0')}</span>
      <div class="fila-textos">
        <div class="fila-titulo">${escapar(f.titulo)}</div>
        <div class="fila-estado">
          <span class="${f.bajado ? 'si' : 'no'}">${f.bajado ? 'bajado' : 'sin bajar'}</span>
          ${f.bpm ? `<span class="tenue">${Math.round(f.bpm)} BPM</span>` : ''}
          ${f.bajado && !f.analizado ? '<span class="tenue">sin analizar</span>' : ''}
        </div>
      </div>
      <span class="fila-mb">${f.mb ? `${f.mb} MB` : ''}</span>
      <button class="icono subir" aria-label="Subir" ${f.pos === 1 ? 'disabled' : ''}>
        <svg viewBox="0 0 24 24"><path d="M6 15l6-6 6 6"/></svg></button>
      <button class="icono bajar" aria-label="Bajar" ${f.pos === ultimo ? 'disabled' : ''}>
        <svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>
      <button class="icono buscar" aria-label="Cambiar video">
        <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></svg></button>
      <button class="icono quitar" aria-label="Quitar">
        <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>`

    li.querySelector('.subir').onclick = () => mover(f.pos, -1)
    li.querySelector('.bajar').onclick = () => mover(f.pos, 1)
    li.querySelector('.buscar').onclick = () => abrirBuscador(f.pos)
    // Quitar saca el tema Y su audio: pide un segundo toque.
    li.querySelector('.quitar').onclick = (e) =>
      pedirConfirmacion(e.currentTarget, () => quitar(f.pos), 'Quitar')
    ol.appendChild(li)
  }
}

async function recargar () {
  await Promise.all([cargarFilas(), cargarReceta()])
  if (panelActual === 'tramos') pintarTramos()
  if (panelActual === 'armar') pintarArmar()
}

async function mover (pos, direccion) {
  await api(`/api/sets/${estado.set}/mover`, {
    method: 'POST', body: JSON.stringify({ desde: pos, direccion }),
  })
  await recargar()
}

async function quitar (pos) {
  await api(`/api/sets/${estado.set}/filas/${pos}`, { method: 'DELETE' })
  await recargar()
  avisar('Quitado')
}

$('#btn-agregar').onclick = () => abrirBuscador(null)

$('#btn-bajar').onclick = async () => {
  await correrTarea(`/api/sets/${estado.set}/bajar`, {}, 'Bajando')
  await recargar()
  await cargarSets()
}

// ------------------------------------------------------- texto crudo

$('#crudo').addEventListener('toggle', async (e) => {
  if (!e.target.open) return
  const { contenido } = await api(`/api/sets/${estado.set}/lista`)
  $('#editor-crudo').value = contenido
})

let confirmarRecorte = false
$('#guardar-crudo').onclick = async () => {
  const ruta = `/api/sets/${estado.set}/lista${confirmarRecorte ? '?confirmar=true' : ''}`
  try {
    await api(ruta, { method: 'PUT', body: JSON.stringify({ contenido: $('#editor-crudo').value }) })
    confirmarRecorte = false
    avisar('Lista guardada')
    await recargar()
  } catch (e) {
    // El server frena los recortes grandes. Sin dialogos del navegador:
    // se avisa y el segundo clic confirma.
    if (e.status === 409) {
      confirmarRecorte = true
      avisar(`${e.message} Volvé a tocar "Guardar texto" para confirmar.`)
    } else avisar(e.message)
  }
}

// ============================================================= buscador

const busqueda = {
  paraFila: null,   // null = agregar (seleccion multiple); numero = cambiar esa fila
  consulta: '',
  resultados: [],
  elegidos: new Map(),
  hasta: 0,
  hayMas: false,
  cargando: false,
}
const POR_PAGINA = 20

function abrirBuscador (paraFila) {
  busqueda.paraFila = paraFila
  busqueda.resultados = []
  busqueda.elegidos.clear()
  busqueda.hayMas = false

  const multiple = paraFila === null
  $('#buscar-titulo').textContent = multiple
    ? 'Agregar temas'
    : `Cambiar el video del tema ${paraFila}`
  $('#buscar-ayuda').textContent = multiple
    ? 'Marcá todos los que quieras. Escuchalos en YouTube antes: el primer resultado suele ser un cover o un remix.'
    : 'El primer resultado suele ser un cover o un remix. Escuchalo antes de elegir.'

  const fila = estado.filas.find((f) => f.pos === paraFila)
  $('#consulta').value = fila && fila.tipo === 'busqueda' ? fila.titulo : ''
  pintarResultados()
  $('#hoja-buscar').showModal()
  $('#consulta').focus()
  if ($('#consulta').value) buscar()
}

async function buscar () {
  const q = $('#consulta').value.trim()
  if (!q) return
  // Buscar esconde el teclado del celular: si no, tapa los resultados.
  $('#consulta').blur()
  busqueda.consulta = q
  busqueda.resultados = []
  busqueda.hasta = 0
  busqueda.hayMas = true
  pintarResultados()
  await traerPagina()
}

async function traerPagina () {
  if (busqueda.cargando || !busqueda.hayMas) return
  busqueda.cargando = true
  $('#buscando').hidden = false
  const desde = busqueda.hasta + 1
  const hasta = busqueda.hasta + POR_PAGINA
  try {
    const nuevos = await api(
      `/api/buscar?q=${encodeURIComponent(busqueda.consulta)}&desde=${desde}&hasta=${hasta}`)
    const ya = new Set(busqueda.resultados.map((r) => r.id))
    const frescos = nuevos.filter((r) => !ya.has(r.id))
    busqueda.resultados.push(...frescos)
    busqueda.hasta = hasta
    // YouTube devuelve algo menos de lo pedido: si una pagina no trae nada
    // nuevo, se asume que no hay mas.
    busqueda.hayMas = frescos.length > 0
  } catch (e) {
    busqueda.hayMas = false
    avisar(`No pude buscar: ${e.message}`)
  } finally {
    busqueda.cargando = false
    $('#buscando').hidden = true
    pintarResultados()
  }
}

// Scroll infinito: cuando el final de la lista entra en pantalla, se pide
// la pagina siguiente. Cada pagina tarda un poco mas que la anterior porque
// yt-dlp no tiene offset real y recorre las previas por dentro.
const observador = new IntersectionObserver((entradas) => {
  if (entradas.some((e) => e.isIntersecting)) traerPagina()
}, { root: $('#resultados'), rootMargin: '200px' })

// Respaldo por scroll: IntersectionObserver depende de que el navegador
// este dibujando, y en algunas vistas embebidas no dispara. Con los dos,
// si uno no avisa, avisa el otro (traerPagina ignora el pedido duplicado).
$('#resultados').addEventListener('scroll', () => {
  const ul = $('#resultados')
  if (ul.scrollTop + ul.clientHeight >= ul.scrollHeight - 200) traerPagina()
}, { passive: true })

function pintarResultados () {
  const multiple = busqueda.paraFila === null
  const ul = $('#resultados')
  observador.disconnect()
  ul.innerHTML = ''

  for (const r of busqueda.resultados) {
    const elegido = busqueda.elegidos.has(r.id)
    const li = document.createElement('li')
    li.className = elegido ? 'elegido' : ''
    li.innerHTML = `
      ${multiple ? `<input type="checkbox" ${elegido ? 'checked' : ''} tabindex="-1">` : ''}
      <img src="${r.miniatura}" alt="" loading="lazy">
      <div class="datos">
        <div class="t">${escapar(r.titulo)}</div>
        <div class="m">${escapar(r.canal)} · ${reloj(r.duracion)} · ${vistas(r.vistas)} vistas</div>
      </div>
      <a class="icono" href="${r.url}" target="_blank" rel="noopener" aria-label="Ver en YouTube"
         title="Escucharlo en YouTube">
        <svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg></a>
      ${multiple ? '' : '<button class="primario usar">Usar</button>'}`

    li.querySelector('a').onclick = (e) => e.stopPropagation()
    if (multiple) {
      li.onclick = () => {
        if (busqueda.elegidos.has(r.id)) busqueda.elegidos.delete(r.id)
        else busqueda.elegidos.set(r.id, r)
        pintarResultados()
      }
    } else {
      li.querySelector('.usar').onclick = (e) => { e.stopPropagation(); usar(r) }
    }
    ul.appendChild(li)
  }

  if (busqueda.resultados.length && !busqueda.hayMas && !busqueda.cargando) {
    ul.insertAdjacentHTML('beforeend', '<li class="fin">No hay más resultados</li>')
  }
  const centinela = document.createElement('li')
  centinela.style.height = '1px'
  ul.appendChild(centinela)
  if (busqueda.hayMas) observador.observe(centinela)

  const boton = $('#agregar-elegidos')
  boton.hidden = !multiple
  boton.disabled = !busqueda.elegidos.size
  boton.textContent = busqueda.elegidos.size
    ? `Agregar ${busqueda.elegidos.size}` : 'Marcá los que quieras'
}

async function usar (r) {
  await api(`/api/sets/${estado.set}/filas/${busqueda.paraFila}`, {
    method: 'PUT', body: JSON.stringify({ url: r.url, titulo: r.titulo }),
  })
  $('#hoja-buscar').close()
  await recargar()
  avisar('Video cambiado: tocá "Bajar" para traerlo')
}

$('#agregar-elegidos').onclick = async () => {
  const temas = [...busqueda.elegidos.values()].map((r) => ({ url: r.url, titulo: r.titulo }))
  const r = await api(`/api/sets/${estado.set}/filas`, {
    method: 'POST', body: JSON.stringify({ temas }),
  })
  $('#hoja-buscar').close()
  await recargar()
  avisar(`Agregados ${r.agregados}` + (r.repetidos ? ` (${r.repetidos} ya estaban)` : ''))
}

$('#form-buscar').onsubmit = (e) => { e.preventDefault(); buscar() }
$('#cerrar-buscar').onclick = () => $('#hoja-buscar').close()

// =============================================================== tramos

async function cargarReceta () {
  try { estado.receta = await api(`/api/sets/${estado.set}/receta`) } catch { estado.receta = null }
}

const temas = () => estado.receta?.temas ?? []

let temporizadorGuardado
/**
 * Guarda la receta un instante despues del ultimo cambio. Arrastrar un
 * borde dispara muchos cambios por segundo: mandar la receta entera en cada
 * uno trabaria justo el gesto que mas importa que sea fluido.
 */
function guardarRecetaPronto () {
  refrescarLargoTramo()
  clearTimeout(temporizadorGuardado)
  temporizadorGuardado = setTimeout(() => {
    if (!estado.receta) return
    api(`/api/sets/${estado.set}/receta`, {
      method: 'PUT', body: JSON.stringify(estado.receta),
    }).catch((e) => avisar(`No pude guardar: ${e.message}`))
  }, 500)
}

function pintarTramos () {
  const lista = temas()
  const pendientes = estado.filas.filter((f) => f.bajado && !f.analizado).length
  const bajados = estado.filas.filter((f) => f.bajado).length
  const boton = $('#btn-analizar')

  desarmar(boton)
  boton.hidden = false
  boton.dataset.deshabilitado = '0'
  if (pendientes) {
    boton.textContent = `Analizar pendientes (${pendientes})`
    boton.className = 'primario ancho'
    boton.onclick = () => analizar({ modo: 'nuevos' }, 'Analizando')
  } else if (lista.length) {
    boton.textContent = 'Re-analizar todos'
    boton.className = 'contorno ancho'
    // Rehacer todo pisa los tramos que ajustaste a mano: segundo toque.
    boton.onclick = (e) => pedirConfirmacion(e.currentTarget,
      () => analizar({ modo: 'todos' }, 'Re-analizando'), 'Pisa tus ajustes · tocá de nuevo')
  } else {
    boton.hidden = true
  }
  refrescarBotones()

  if (!lista.length) {
    $('#editor-tramo').hidden = true
    $('#sin-tramos').hidden = false
    $('#sin-tramos').textContent = bajados
      ? 'Analizá los temas y acá elegís qué pedazo suena de cada uno.'
      : 'Primero bajá los temas. Después acá elegís qué pedazo suena de cada uno.'
    return
  }

  $('#sin-tramos').hidden = true
  $('#editor-tramo').hidden = false
  estado.indice = Math.min(estado.indice, lista.length - 1)

  const chips = $('#chips')
  chips.innerHTML = ''
  lista.forEach((t, i) => {
    const b = document.createElement('button')
    b.textContent = i + 1
    b.className = i === estado.indice ? 'activa' : ''
    b.onclick = () => elegirTema(i)
    chips.appendChild(b)
  })
  pintarListaTramos()
  cargarTema()
}

const segundos = (s) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`

// En escritorio, los temas con su tramo al lado del editor: ves el
// enganchado entero y saltas a cualquiera de un click.
function pintarListaTramos () {
  const ol = $('#lista-tramos')
  ol.innerHTML = ''
  temas().forEach((t, i) => {
    const li = document.createElement('li')
    li.className = i === estado.indice ? 'activa' : ''
    li.innerHTML = `
      <span class="n">${String(i + 1).padStart(2, '0')}</span>
      <div class="t">
        <div>${escapar(t.titulo)}</div>
        <small>${Math.round(t.bpm)} BPM</small>
      </div>
      <span class="largo">${segundos(t.fin - t.inicio)}</span>`
    li.onclick = () => elegirTema(i)
    ol.appendChild(li)
  })
  ol.children[estado.indice]?.scrollIntoView({ block: 'nearest' })
}

// Mientras arrastras el tramo, el largo de la lista acompania.
function refrescarLargoTramo () {
  const t = temas()[estado.indice]
  const el = $('#lista-tramos').children[estado.indice]?.querySelector('.largo')
  if (t && el) el.textContent = segundos(t.fin - t.inicio)
}

async function analizar (opciones, titulo) {
  const ok = await correrTarea(`/api/sets/${estado.set}/analizar`, opciones, titulo)
  await recargar()
  if (ok) avisar('Análisis listo')
}

function elegirTema (i) {
  pausarTramo()   // si no, seguis escuchando el anterior mirando la onda del nuevo
  estado.indice = i
  pintarTramos()
}

// ------------------------------------------------------------ la onda

let ondas = null
let regiones = null
let region = null
let aplicando = false   // candado: campos -> region -> campos se llamarian en circulo
let modoTramo = true    // que se esta escuchando: el tramo o el tema entero
let temaCargado = null

const escritorio = matchMedia('(min-width: 1000px)')
escritorio.addEventListener('change', () => ondas?.setOptions({ height: escritorio.matches ? 220 : 150 }))

function crearOndas () {
  regiones = RegionsPlugin.create()
  ondas = WaveSurfer.create({
    container: '#onda',
    waveColor: '#3b4252',
    progressColor: '#5b647a',
    cursorColor: '#ffffff',
    height: escritorio.matches ? 220 : 150,
    normalize: true,
    plugins: [regiones],
  })

  ondas.on('decode', () => {
    const t = temas()[estado.indice]
    if (!t) return
    regiones.clearRegions()
    region = regiones.addRegion({
      start: t.inicio, end: t.fin,
      color: 'rgba(255, 179, 64, .18)',
      drag: true, resize: true,   // del medio se mueve entero; de las manijas, se estira
    })
    mostrarValores()
  })

  const alMover = (r) => {
    if (aplicando) return
    region = r
    const t = temas()[estado.indice]
    t.inicio = Number(r.start.toFixed(2))
    t.fin = Number(r.end.toFixed(2))
    mostrarValores()
  }
  regiones.on('region-update', alMover)                                  // mientras arrastrás
  regiones.on('region-updated', (r) => { alMover(r); guardarRecetaPronto() })  // al soltar

  // "Tramo" frena en el borde derecho. Solo en ese modo: si pediste el tema
  // entero, el tramo no tiene que cortarte.
  regiones.on('region-out', (r) => {
    if (modoTramo && r === region && ondas.isPlaying()) ondas.pause()
  })

  ondas.on('play', pintarBotonesPlay)
  ondas.on('pause', pintarBotonesPlay)
  ondas.on('finish', pintarBotonesPlay)
}

function cargarTema () {
  const t = temas()[estado.indice]
  if (!t) return
  $('#tema-titulo').textContent = t.titulo
  $('#tema-info').textContent = `${Math.round(t.bpm)} BPM · dura ${Math.round(t.duracion_total)}s`
  $('#tema-pos').textContent = `${estado.indice + 1}/${temas().length}`
  $('#mover-antes').disabled = estado.indice === 0
  $('#mover-despues').disabled = estado.indice === temas().length - 1

  if (!ondas) crearOndas()
  // Solo se recarga el audio si cambio el tema: pintarTramos corre seguido
  // y bajar el mismo archivo cada vez seria un desperdicio.
  const clave = `${estado.set}/${t.id}`
  if (clave !== temaCargado) {
    temaCargado = clave
    ondas.load(`/api/sets/${estado.set}/audio/${estado.indice}`)
  } else if (region) {
    aplicando = true
    region.setOptions({ start: t.inicio, end: t.fin })
    aplicando = false
    mostrarValores()
  }
}

function mostrarValores () {
  const t = temas()[estado.indice]
  if (!t) return
  const compas = 60 / t.bpm * 4
  const compases = t.bpm ? Math.round((t.fin - t.inicio) / compas) : 0
  $('#valores').textContent =
    `${t.inicio.toFixed(1)}s → ${t.fin.toFixed(1)}s   (${Math.round(t.fin - t.inicio)}s · ${compases} compases)`
  $('#campo-inicio').value = t.inicio.toFixed(1)
  $('#campo-fin').value = t.fin.toFixed(1)
}

/** Unico camino para cambiar un tramo: receta, region y campos a la vez. */
function aplicarTramo (inicio, fin) {
  const t = temas()[estado.indice]
  if (!t) return
  const dur = t.duracion_total
  inicio = Math.max(0, Math.min(inicio, dur - 1))
  fin = Math.max(inicio + 1, Math.min(fin, dur))
  t.inicio = Number(inicio.toFixed(2))
  t.fin = Number(fin.toFixed(2))
  if (region) {
    aplicando = true
    region.setOptions({ start: t.inicio, end: t.fin })
    aplicando = false
  }
  mostrarValores()
  guardarRecetaPronto()
}

const desdeCampos = () => aplicarTramo(Number($('#campo-inicio').value), Number($('#campo-fin').value))
$('#campo-inicio').onchange = desdeCampos
$('#campo-fin').onchange = desdeCampos

// Redondea el LARGO a compases enteros, dejando fijo el arranque.
$('#pegar-compas').onclick = () => {
  const t = temas()[estado.indice]
  if (!t?.bpm) return
  const compas = 60 / t.bpm * 4
  const n = Math.max(1, Math.round((t.fin - t.inicio) / compas))
  aplicarTramo(t.inicio, t.inicio + n * compas)
}

$('#re-analizar').onclick = () =>
  analizar({ modo: 'solo', solo: [estado.indice + 1] }, 'Re-analizando este tema')

async function moverTema (direccion) {
  const t = temas()[estado.indice]
  const fila = estado.filas.find((f) => f.id === t?.id)
  if (!fila) return
  await mover(fila.pos, direccion)
  // la seleccion viaja con el tema
  const nuevo = temas().findIndex((x) => x.id === t.id)
  if (nuevo >= 0) estado.indice = nuevo
  pintarTramos()
}
$('#mover-antes').onclick = () => moverTema(-1)
$('#mover-despues').onclick = () => moverTema(1)

// ------------------------------------------------------ reproducir tramo

function pintarBotonesPlay () {
  const sonando = ondas?.isPlaying()
  $('#tocar-tramo').textContent = sonando && modoTramo ? '❚❚ Pausar' : '▶ Tramo'
  $('#tocar-entero').textContent = sonando && !modoTramo ? '❚❚ Pausar' : '▶ Tema entero'
}

function pausarTramo () {
  if (ondas?.isPlaying()) ondas.pause()
}

$('#tocar-tramo').onclick = () => {
  if (!region || !ondas) return
  if (ondas.isPlaying() && modoTramo) return ondas.pause()
  pausarMezcla()
  modoTramo = true
  // si el cursor quedo dentro del tramo, sigue de ahi: al ajustar bordes
  // se escucha el mismo pedazo muchas veces seguidas
  const t = ondas.getCurrentTime()
  if (t >= region.start && t < region.end - 0.05) ondas.play()
  else region.play()
}

$('#tocar-entero').onclick = () => {
  if (!ondas) return
  if (ondas.isPlaying() && !modoTramo) return ondas.pause()
  pausarMezcla()
  modoTramo = false
  ondas.play(0)   // desde el principio: la linea blanca muestra donde esta el estribillo
}

// Barra espaciadora: play/pausa del tramo, como cualquier editor de audio.
document.addEventListener('keydown', (e) => {
  if (e.code !== 'Space' || panelActual !== 'tramos') return
  if (/^(INPUT|TEXTAREA|SELECT|BUTTON)$/.test(e.target.tagName)) return
  e.preventDefault()
  $('#tocar-tramo').click()
})

// ================================================================ armar

function pintarArmar () {
  const lista = temas()
  const cruce = estado.receta?.crossfade_seg ?? 4
  const largo = lista.reduce((s, t) => s + (t.fin - t.inicio), 0) - cruce * Math.max(0, lista.length - 1)

  $('#armar-nombre').textContent = estado.set
  $('#armar-info').textContent = lista.length
    ? `${lista.length} temas listos · ${Math.max(0, Math.round(largo / 60))} min estimados`
    : 'Todavía no hay temas analizados'
  $('#cruce').value = cruce
  $('#cruce-valor').textContent = `${Number(cruce).toFixed(1)}s`

  $('#btn-armar').dataset.deshabilitado = lista.length >= 2 ? '0' : '1'
  $('#btn-stems').dataset.deshabilitado = lista.length >= 1 ? '0' : '1'
  refrescarBotones()

  const s = estado.sets.find((x) => x.nombre === estado.set)
  $('#reproductor').hidden = !s?.tiene_salida
  $('#descargar-zip').hidden = !s?.tiene_stems
  $('#descargar-zip').href = `/api/sets/${estado.set}/zip`
}

$('#cruce').oninput = (e) => {
  if (!estado.receta) return
  estado.receta.crossfade_seg = Number(e.target.value)
  $('#cruce-valor').textContent = `${Number(e.target.value).toFixed(1)}s`
  guardarRecetaPronto()
  pintarArmar()
}

/**
 * Donde arranca cada transicion dentro de la mezcla: cada tramo se escribe
 * menos su cola de cruce, asi que el tema siguiente entra exactamente cuando
 * termina el largo visible del anterior.
 */
function calcularCruces () {
  let t = 0
  return temas().slice(0, -1).map((x) => (t += x.fin - x.inicio))
}

$('#btn-armar').onclick = async () => {
  pausarMezcla()
  // Mientras se arma, el resultado viejo no se ofrece: el archivo se esta
  // sobreescribiendo, escucharlo o bajarlo daria algo a medio escribir.
  $('#reproductor').hidden = true
  const cruces = calcularCruces()   // de la receta CON la que se mezcla
  const ok = await correrTarea(`/api/sets/${estado.set}/renderizar`,
    { crossfade: estado.receta?.crossfade_seg ?? 4 }, 'Armando')
  await cargarSets()
  if (ok) {
    estado.cruces = cruces
    prepararReproductor()
    avisar('Enganchado listo')
  }
  pintarArmar()
}

$('#btn-stems').onclick = async () => {
  $('#descargar-zip').hidden = true
  const ok = await correrTarea(`/api/sets/${estado.set}/renderizar`,
    { stems: true, crossfade: estado.receta?.crossfade_seg ?? 4 }, 'Exportando stems')
  await cargarSets()
  pintarArmar()
  if (ok) avisar('Stems listos: tocá "Descargar ZIP"')
}

// ------------------------------------------------------ reproducir mezcla

const audio = $('#audio-mezcla')
let arrastrandoBarra = false

function prepararReproductor () {
  audio.pause()
  audio.src = `/api/sets/${estado.set}/salida?t=${Date.now()}`   // sin cache: puede ser nueva
  $('#descargar-m4a').href = `/api/sets/${estado.set}/salida?descargar=true`
  const n = estado.cruces.length
  $('#cruces-ayuda').textContent = n
    ? `⏮ ⏭ saltan a 3 s antes de cada cruce entre temas (${n} en total): ahí se escucha si la transición quedó bien.`
    : ''
  $('#cruce-anterior').disabled = !n
  $('#cruce-siguiente').disabled = !n
}

function pausarMezcla () { if (!audio.paused) audio.pause() }

audio.onloadedmetadata = () => {
  $('#mezcla-pos').max = audio.duration || 1
  $('#mezcla-total').textContent = reloj(audio.duration)
}
audio.ontimeupdate = () => {
  if (arrastrandoBarra) return   // que la barra no le pelee al dedo
  $('#mezcla-pos').value = audio.currentTime
  $('#mezcla-ahora').textContent = reloj(audio.currentTime)
}
const pintarPlay = () => {
  $('#icono-play').setAttribute('d', audio.paused ? 'M8 5l11 7-11 7z' : 'M7 5h4v14H7zM13 5h4v14h-4z')
}
audio.onplay = pintarPlay
audio.onpause = pintarPlay
audio.onended = pintarPlay

$('#mezcla-play').onclick = () => {
  if (audio.paused) { pausarTramo(); audio.play() } else audio.pause()
}
$('#mezcla-pos').oninput = (e) => {
  arrastrandoBarra = true
  $('#mezcla-ahora').textContent = reloj(e.target.value)
}
$('#mezcla-pos').onchange = (e) => {
  audio.currentTime = Number(e.target.value)
  arrastrandoBarra = false
}

/**
 * Salta a 3 s ANTES del cruce siguiente o anterior. Para evaluar un
 * enganchado no hace falta escuchar todo: lo que puede salir mal son las
 * transiciones, y el margen deja oir como venia el tema antes del cruce.
 */
function irACruce (direccion) {
  const margen = 3
  const ahora = audio.currentTime
  const destino = direccion > 0
    ? estado.cruces.find((c) => c - margen > ahora + 0.5)
    : [...estado.cruces].reverse().find((c) => c - margen < ahora - 1)
  if (destino === undefined) return
  audio.currentTime = Math.max(0, destino - margen)
  if (audio.paused) { pausarTramo(); audio.play() }
}
$('#cruce-anterior').onclick = () => irACruce(-1)
$('#cruce-siguiente').onclick = () => irACruce(1)

// ========================================================= app instalada

/**
 * Instalada como app, "descargar" no tiene sentido: los archivos ya estan
 * en la compu, en Documentos/Enganchados. Ahi los botones abren el
 * explorador de archivos con el archivo seleccionado, que es mas claro para
 * quien no sabe donde termino lo que bajo.
 */
async function prepararModoApp () {
  let info
  try { info = await api('/api/info') } catch { return }
  if (info.modo !== 'app') return

  const mostrar = (que) => async (e) => {
    e.preventDefault()
    try {
      const set = que === 'carpeta' ? '' : `&set=${encodeURIComponent(estado.set)}`
      await api(`/api/mostrar?que=${que}${set}`, { method: 'POST' })
    } catch (err) { avisar(err.message) }
  }

  $('#descargar-m4a').textContent = 'Mostrar el m4a en la carpeta'
  $('#descargar-m4a').onclick = mostrar('m4a')
  $('#descargar-zip').textContent = 'Mostrar el ZIP en la carpeta'
  $('#descargar-zip').onclick = mostrar('zip')
  $('#abrir-carpeta').onclick = mostrar('carpeta')

  const carpeta = await api('/api/carpeta-datos')
  $('#carpeta-datos').hidden = false
  $('#ruta-datos').textContent = carpeta.datos
  $('#cambiar-carpeta').hidden = !carpeta.puede_cambiar
  $('#cambiar-carpeta').onclick = cambiarCarpeta

  $('#lateral-carpeta').hidden = false
  $('#lateral-ruta').textContent = carpeta.datos
  $('#lateral-abrir').onclick = mostrar('carpeta')
  $('#lateral-cambiar').hidden = !carpeta.puede_cambiar
  $('#lateral-cambiar').onclick = cambiarCarpeta
}

/**
 * Cambia donde se guardan los enganchados. Si la carpeta nueva esta vacia,
 * se mudan los que habia; despues la app se reinicia sola para usarla.
 */
async function cambiarCarpeta () {
  const boton = $('#cambiar-carpeta')
  boton.disabled = true
  boton.textContent = 'Eligiendo…'
  try {
    const r = await api('/api/carpeta-datos', { method: 'POST' })
    if (!r.cambiado) return
    avisar(r.movido
      ? 'Listo, tus enganchados se mudaron. Reiniciando…'
      : 'Listo, uso los enganchados de esa carpeta. Reiniciando…')
    setTimeout(() => api('/api/reiniciar', { method: 'POST' }).catch(() => {}), 1500)
  } catch (e) {
    avisar(e.message)
  } finally {
    boton.disabled = false
    boton.textContent = 'Cambiar…'
  }
}

// ============================================================== arranque

await prepararModoApp()
await cargarSets()
const ultimo = guardadoLocal.leer('enganchado')
const inicial = estado.sets.find((s) => s.nombre === ultimo)?.nombre ?? estado.sets[0]?.nombre
if (inicial) await abrirSet(inicial)
else mostrarBienvenida()
