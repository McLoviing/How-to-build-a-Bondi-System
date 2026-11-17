"""Gestión de líneas y tramos dirigidos, conservando los hechos no editados."""
import re
from fastapi import Header, HTTPException
from pydantic import BaseModel, Field
from admin_paradas import _autorizar, _consultar, _escribir_atomico, serializar_prolog
from config import ARCHIVO_PROLOG
from servicios_prolog import obtener_lineas


class DatosLinea(BaseModel):
    numero: str = Field(pattern=r'^[0-9]{1,4}[A-Za-z]?$')


class DatosTramo(BaseModel):
    origen: str = Field(pattern=r'^p_[0-9]+$')
    destino: str = Field(pattern=r'^p_[0-9]+$')
    distancia: float = Field(gt=0, le=100000, allow_inf_nan=False)
    tiempo: float = Field(gt=0, le=600, allow_inf_nan=False)


def _linea(numero):
    if not re.fullmatch(r'[0-9]{1,4}[A-Za-z]?', numero):
        raise HTTPException(422, 'Número de línea inválido')
    return "'" + numero + "'"


def _tramos(prolog, numero):
    l = _linea(numero)
    return [dict(origen=str(r['O']), destino=str(r['D']), distancia=float(r['M']), tiempo=float(r['T']))
            for r in _consultar(prolog, f'tramo({l}, O, D, M, T)')]


def _guardar(estado, transformar, consulta):
    ruta = ARCHIVO_PROLOG.resolve()
    original = ruta.read_bytes()
    texto = original.decode('utf-8')
    nuevo = transformar(texto.replace('\r\n', '\n'))
    if '\r\n' in texto:
        nuevo = nuevo.replace('\n', '\r\n')
    _escribir_atomico(ruta, nuevo.encode('utf-8'))
    try:
        if not _consultar(estado['prolog'], f'transaction(({consulta}))'):
            raise RuntimeError('Transacción fallida')
    except Exception:
        _escribir_atomico(ruta, original)
        raise HTTPException(500, 'No se pudo modificar Prolog; se restauró base.pl')
    estado['lineas'] = obtener_lineas(estado['prolog'])
    estado['cache_rutas'].clear()


def _insertar(texto, predicado, hechos):
    if not hechos:
        return texto
    m = re.search(r'^' + predicado + r'\(', texto, re.M)
    if not m:
        raise HTTPException(409, 'No se encontró la sección de ' + predicado)
    return texto[:m.start()] + ''.join(h + '.\n' for h in hechos) + texto[m.start():]


def _patron(predicado, l, resto):
    return re.compile(r'^' + predicado + r'\(' + re.escape(l) + resto + r'\)\.[ \t]*\n?', re.M)


def registrar_lineas(app, estado):
    def existe(numero):
        l = _linea(numero)
        if numero not in estado['lineas']:
            raise HTTPException(404, 'Línea inexistente')
        return l

    @app.get('/admin/lineas', tags=['Administrador'])
    @serializar_prolog
    def listar(x_admin_token: str = Header(default='')):
        _autorizar(x_admin_token)
        return estado['lineas']

    @app.post('/admin/lineas', status_code=201, tags=['Administrador'])
    @serializar_prolog
    def crear(datos: DatosLinea, x_admin_token: str = Header(default='')):
        _autorizar(x_admin_token)
        l = _linea(datos.numero)
        if datos.numero in estado['lineas']:
            raise HTTPException(409, 'La línea ya existe')
        hecho = f'linea({l})'
        _guardar(estado, lambda t: _insertar(t, 'linea', [hecho]), f'assertz({hecho})')
        return {'numero': datos.numero}

    @app.put('/admin/lineas/{numero}', tags=['Administrador'])
    @serializar_prolog
    def renombrar(numero: str, datos: DatosLinea, x_admin_token: str = Header(default='')):
        _autorizar(x_admin_token)
        l = existe(numero)
        n = _linea(datos.numero)
        if numero == datos.numero:
            return {'numero': numero}
        if datos.numero in estado['lineas']:
            raise HTTPException(409, 'La línea ya existe')
        def cambiar(t):
            for pred in ('linea', 'pasa_por', 'tramo'):
                t = re.sub(r'^' + pred + r'\(' + re.escape(l) + r'(?=[,)])',
                           lambda _: pred + '(' + n, t, flags=re.M)
            return t
        consulta = (f'retractall(linea({l})), assertz(linea({n})), '
                    f'findall(P,pasa_por({l},P),Ps), retractall(pasa_por({l},_)), '
                    f'forall(member(P,Ps),assertz(pasa_por({n},P))), '
                    f'findall(t(O,D,M,T),tramo({l},O,D,M,T),Ts), retractall(tramo({l},_,_,_,_)), '
                    f'forall(member(t(O,D,M,T),Ts),assertz(tramo({n},O,D,M,T)))')
        _guardar(estado, cambiar, consulta)
        return {'numero': datos.numero}

    @app.delete('/admin/lineas/{numero}', tags=['Administrador'])
    @serializar_prolog
    def eliminar(numero: str, x_admin_token: str = Header(default='')):
        _autorizar(x_admin_token)
        l = existe(numero)
        if _tramos(estado['prolog'], numero) or _consultar(estado['prolog'], f'once(pasa_por({l},_))'):
            raise HTTPException(409, 'Solo se pueden eliminar líneas sin paradas ni tramos')
        _guardar(estado, lambda t: _patron('linea', l, '').sub('', t), f'retractall(linea({l}))')
        return {'eliminada': numero}

    @app.get('/admin/lineas/{numero}/tramos', tags=['Administrador'])
    @serializar_prolog
    def listar_tramos(numero: str, x_admin_token: str = Header(default='')):
        _autorizar(x_admin_token)
        existe(numero)
        return _tramos(estado['prolog'], numero)

    def modificar(numero, o, d, datos):
        l = existe(numero)
        if not all(re.fullmatch(r'p_[0-9]+', p) and p in estado['dict_paradas'] for p in (o,d)) or o == d:
            raise HTTPException(422, 'Elegí dos paradas distintas y existentes')
        prolog = estado['prolog']
        anteriores = _tramos(prolog, numero)
        hay = any(t['origen'] == o and t['destino'] == d for t in anteriores)
        if datos is None and not hay:
            raise HTTPException(404, 'Tramo inexistente')
        miembros = {str(r['P']) for r in _consultar(prolog, f'pasa_por({l},P)')}
        restantes = [t for t in anteriores if (t['origen'],t['destino']) != (o,d)]
        usados = {p for t in restantes for p in (t['origen'],t['destino'])}
        agregar = {o,d} - miembros if datos else set()
        quitar = ({o,d} & miembros) - usados if datos is None else set()
        hecho = f'tramo({l}, {o}, {d}, {datos.distancia!r}, {datos.tiempo!r})' if datos else None
        def cambiar(t):
            t = _patron('tramo', l, rf',\s*{o},\s*{d},[^\r\n]*').sub('', t)
            for p in quitar:
                t = _patron('pasa_por', l, rf',\s*{p}').sub('', t)
            t = _insertar(t, 'pasa_por', [f'pasa_por({l}, {p})' for p in sorted(agregar)])
            return _insertar(t, 'tramo', [hecho] if hecho else [])
        q = [f'retractall(tramo({l},{o},{d},_,_))']
        if hecho:
            q.append(f'assertz({hecho})')
        q.extend(f'retractall(pasa_por({l},{p}))' for p in sorted(quitar))
        q.extend(f'assertz(pasa_por({l},{p}))' for p in sorted(agregar))
        _guardar(estado, cambiar, ', '.join(q))
        return {'origen': o, 'destino': d}

    @app.put('/admin/lineas/{numero}/tramos', tags=['Administrador'])
    @serializar_prolog
    def guardar_tramo(numero: str, datos: DatosTramo, x_admin_token: str = Header(default='')):
        _autorizar(x_admin_token)
        return modificar(numero, datos.origen, datos.destino, datos)

    @app.delete('/admin/lineas/{numero}/tramos/{origen}/{destino}', tags=['Administrador'])
    @serializar_prolog
    def eliminar_tramo(numero: str, origen: str, destino: str, x_admin_token: str = Header(default='')):
        _autorizar(x_admin_token)
        return modificar(numero, origen, destino, None)
