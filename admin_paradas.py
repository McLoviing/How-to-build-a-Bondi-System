"""Administración de paradas: persistencia de hechos y actualización del grafo."""
import hmac
import os
import re
import tempfile
from functools import wraps
from pathlib import Path

from fastapi import Header, HTTPException
from pydantic import BaseModel, Field, field_validator

from config import ARCHIVO_PROLOG
from servicios_prolog import _PROLOG_LOCK, obtener_paradas


class DatosParada(BaseModel):
    calle: str = Field(min_length=1, max_length=150)
    referencia: str = Field(default="", max_length=150)
    latitud: float = Field(ge=-90, le=90, allow_inf_nan=False)
    longitud: float = Field(ge=-180, le=180, allow_inf_nan=False)

    @field_validator("calle", "referencia")
    @classmethod
    def limpiar_texto(cls, valor):
        valor = valor.strip()
        if any(ord(c) < 32 or ord(c) == 127 for c in valor):
            raise ValueError("El texto no puede contener caracteres de control")
        return valor


def serializar_prolog(funcion):
    """Evita consultas de rutas mientras se modifica el estado compartido."""
    @wraps(funcion)
    def ejecutar(*args, **kwargs):
        with _PROLOG_LOCK:
            return funcion(*args, **kwargs)
    return ejecutar


def _autorizar(token):
    esperado = os.environ.get("BONDI_ADMIN_TOKEN", "")
    if not esperado:
        raise HTTPException(503, "Administración deshabilitada: configurar BONDI_ADMIN_TOKEN")
    if not token or not hmac.compare_digest(token.encode(), esperado.encode()):
        raise HTTPException(403, "Clave de administrador incorrecta")


def _consultar(prolog, consulta):
    q = prolog.query(consulta)
    try:
        return list(q)
    finally:
        q.close()


def _atomo(valor):
    return "'" + valor.replace("\\", "\\\\").replace("'", "''") + "'"


def _hecho(identificador, datos):
    return (f"parada({identificador}, {_atomo(datos.calle)}, "
            f"{_atomo(datos.referencia)}, {datos.latitud!r}, {datos.longitud!r})")


def _detalle(prolog):
    vinculadas = {str(r["P"]) for r in _consultar(prolog, "pasa_por(_, P)")}
    for r in _consultar(prolog, "tramo(L, O, D, _, _), L \\== 'CAMINANDO'"):
        vinculadas.update((str(r["O"]), str(r["D"])))
    return [{"id": str(r["Id"]), "calle": str(r["Calle"]),
             "referencia": str(r["Ref"]), "latitud": float(r["Lat"]),
             "longitud": float(r["Lon"]), "vinculada": str(r["Id"]) in vinculadas}
            for r in _consultar(prolog, "parada(Id, Calle, Ref, Lat, Lon)")]


def _reemplazar_hecho(texto, identificador, hecho, existe):
    patron = re.compile(r"^parada\(" + re.escape(identificador) + r",[^\r\n]*\)\.[ \t]*$", re.M)
    if existe:
        nuevo, cantidad = patron.subn(lambda _: hecho + "." if hecho else "", texto)
        if cantidad != 1:
            raise HTTPException(409, "No se encontró un único hecho en base.pl")
        return nuevo
    primera = re.search(r"^parada\(", texto, re.M)
    if not primera:
        raise HTTPException(409, "No se encontró la sección de paradas")
    return texto[:primera.start()] + hecho + ".\n" + texto[primera.start():]


def _escribir_atomico(ruta, contenido):
    with tempfile.NamedTemporaryFile(dir=ruta.parent, delete=False) as temporal:
        nombre = Path(temporal.name)
        try:
            temporal.write(contenido)
            temporal.flush()
            os.fsync(temporal.fileno())
        except Exception:
            temporal.close()
            nombre.unlink(missing_ok=True)
            raise
    try:
        os.replace(nombre, ruta)
    finally:
        nombre.unlink(missing_ok=True)


def _guardar(estado, identificador, datos, existe):
    ruta = ARCHIVO_PROLOG.resolve()
    original = ruta.read_bytes()
    texto = original.decode("utf-8")
    # Conservar CRLF si el archivo usa el formato de Windows.
    normalizado = texto.replace("\r\n", "\n")
    hecho = _hecho(identificador, datos) if datos else None
    nuevo = _reemplazar_hecho(normalizado, identificador, hecho, existe)
    if "\r\n" in texto:
        nuevo = nuevo.replace("\n", "\r\n")
    consulta = f"retractall(parada({identificador}, _, _, _, _))"
    if hecho:
        consulta += f", assertz({hecho})"
    consulta += ", precalcular_caminatas(300.0)"
    prolog = estado["prolog"]
    _escribir_atomico(ruta, nuevo.encode("utf-8"))
    try:
        if not _consultar(prolog, f"transaction(({consulta}))"):
            raise RuntimeError("La transacción de Prolog falló")
    except Exception:
        _escribir_atomico(ruta, original)
        raise HTTPException(500, "No se pudo actualizar Prolog; se restauró base.pl")
    estado["paradas"] = obtener_paradas(prolog)
    estado["dict_paradas"] = {p["id"]: p for p in estado["paradas"]}
    estado["cache_rutas"].clear()


def registrar_admin(app, estado):
    @app.get("/admin/paradas", tags=["Administrador"])
    @serializar_prolog
    def listar(x_admin_token: str = Header(default="")):
        _autorizar(x_admin_token)
        return _detalle(estado["prolog"])

    @app.post("/admin/paradas", status_code=201, tags=["Administrador"])
    @serializar_prolog
    def agregar(datos: DatosParada, x_admin_token: str = Header(default="")):
        _autorizar(x_admin_token)
        if not datos.calle:
            raise HTTPException(422, "La calle es obligatoria")
        ids = estado["dict_paradas"]
        maximo = max((int(i[2:]) for i in ids if re.fullmatch(r"p_\d+", i)), default=0)
        identificador = f"p_{maximo + 1}"
        _guardar(estado, identificador, datos, False)
        return estado["dict_paradas"][identificador]

    @app.put("/admin/paradas/{identificador}", tags=["Administrador"])
    @serializar_prolog
    def editar(identificador: str, datos: DatosParada, x_admin_token: str = Header(default="")):
        _autorizar(x_admin_token)
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", identificador):
            raise HTTPException(422, "Identificador inválido")
        anterior = next((p for p in _detalle(estado["prolog"]) if p["id"] == identificador), None)
        if anterior is None:
            raise HTTPException(404, "Parada inexistente")
        if not datos.calle:
            raise HTTPException(422, "La calle es obligatoria")
        if anterior["vinculada"] and (datos.latitud != anterior["latitud"] or datos.longitud != anterior["longitud"]):
            raise HTTPException(409, "No se pueden mover paradas vinculadas sin revisar los tramos de colectivo")
        _guardar(estado, identificador, datos, True)
        return estado["dict_paradas"][identificador]

    @app.delete("/admin/paradas/{identificador}", tags=["Administrador"])
    @serializar_prolog
    def eliminar(identificador: str, x_admin_token: str = Header(default="")):
        _autorizar(x_admin_token)
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", identificador):
            raise HTTPException(422, "Identificador inválido")
        anterior = next((p for p in _detalle(estado["prolog"]) if p["id"] == identificador), None)
        if anterior is None:
            raise HTTPException(404, "Parada inexistente")
        if anterior["vinculada"]:
            raise HTTPException(409, "La parada pertenece a recorridos de colectivo")
        _guardar(estado, identificador, None, True)
        return {"eliminada": identificador}
