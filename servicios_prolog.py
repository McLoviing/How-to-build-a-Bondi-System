"""
Servicio de comunicación y consultas con el motor de Prolog (SWI-Prolog / PySwip).
Usa el puente JSON nativo (buscar_rutas_json/4) para cero-parsing en la frontera C-Python.
"""
import json
import logging
import os
import re
import sys
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

# Garantizar resolución de libswipl.dll en Windows antes de importar pyswip
if sys.platform == "win32":
    _ruta_swipl_bin = Path(os.environ.get("SWI_HOME_DIR") or r"C:\Program Files\swipl") / "bin"
    if _ruta_swipl_bin.is_dir():
        os.environ["PATH"] = f"{_ruta_swipl_bin};{os.environ.get('PATH', '')}"
        if hasattr(os, "add_dll_directory"):
            _swipl_dll_handle = os.add_dll_directory(str(_ruta_swipl_bin))

from pyswip import Prolog
from config import CANTIDAD_ALTERNATIVAS
from resguardo_admin import obtener_archivo_trabajo

logger = logging.getLogger(__name__)

# Candado reentrante compartido con admin_paradas.py y api.py para serializar acceso a PySwip
_PROLOG_LOCK = threading.RLock()

_PATRON_ATOMO_SEGURO = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
_PATRON_NATURAL_SORT = re.compile(r"(\d+)")


def _validar_atomo_prolog(atomo: str) -> str:
    if not isinstance(atomo, str) or not _PATRON_ATOMO_SEGURO.match(atomo):
        raise ValueError(f"Identificador inválido para Prolog: '{atomo}'")
    return atomo


def _clave_orden_natural(texto: str) -> list:
    partes = _PATRON_NATURAL_SORT.split(texto.lower())
    return [
        (0, int(parte)) if parte.isdigit() else (1, parte)
        for parte in partes if parte
    ]


def _ejecutar_once(prolog: Prolog, objetivo: str) -> bool:
    """
    Evalúa un objetivo Prolog (sin punto final) y cierra su cursor en C
    de forma garantizada (patrón RAII sobre PL_open_query / PL_close_query).
    """
    q = prolog.query(objetivo)
    try:
        return next(q, None) is not None
    finally:
        q.close()


def _purgar_hechos_en_memoria(prolog: Prolog) -> None:
    """
    Elimina cláusulas previas (tanto de archivo como assertz dinámicos) en la VM global
    de SWI-Prolog y restaura explícitamente su atributo dynamic/1 tras abolish/1.
    """
    predicados_datos = [
        "linea/1",
        "parada/5",
        "tramo/5",
        "pasa_por/2",
    ]
    for spec in predicados_datos:
        _ejecutar_once(prolog, f"ignore(abolish({spec}))")
        _ejecutar_once(prolog, f"dynamic({spec})")


def cargar_prolog(ruta_archivo: Optional[Path] = None) -> Prolog:
    """
    Carga overlay_admin.pl (o base.pl), purga estado residual en memoria, ejecuta
    la inicialización topológica del grafo y valida los arcos peatonales.
    """
    ruta_resuelta = (ruta_archivo or obtener_archivo_trabajo()).resolve()
    if not ruta_resuelta.is_file():
        raise FileNotFoundError(f"No se encontró la base de conocimiento Prolog en: {ruta_resuelta}")

    with _PROLOG_LOCK:
        prolog = Prolog()

        # 1. Purgar hechos previos en la VM C de SWI-Prolog (garantiza rollbacks limpios)
        _purgar_hechos_en_memoria(prolog)

        # 2. Consultar archivo de trabajo actual
        prolog.consult(ruta_resuelta.as_posix())

        # 3. Ejecutar inicialización topológica del grafo multimodal (Fail-Fast)
        if _ejecutar_once(prolog, "current_predicate(inicializar_grafo/0)"):
            if not _ejecutar_once(prolog, "inicializar_grafo"):
                raise RuntimeError("Fallo al ejecutar inicializar_grafo/0 en SWI-Prolog.")
        elif _ejecutar_once(prolog, "current_predicate(precalcular_caminatas/1)"):
            if not _ejecutar_once(prolog, "precalcular_caminatas(300.0)"):
                raise RuntimeError("Fallo al ejecutar precalcular_caminatas(300.0) en SWI-Prolog.")

        # 4. Confirmar que los tramos peatonales quedaron assertados en memoria
        q_walk = prolog.query("findall(1, tramo('CAMINANDO', _, _, _, _), L), length(L, Total)")
        try:
            res_walk = next(q_walk, None)
            total_caminatas = int(res_walk["Total"]) if res_walk else 0
            if total_caminatas == 0:
                logger.warning("Advertencia: No se generaron arcos 'CAMINANDO' en memoria.")
            else:
                logger.info(
                    "Grafo Prolog cargado desde '%s' (%d arcos peatonales indexados).",
                    ruta_resuelta.name,
                    total_caminatas,
                )
        finally:
            q_walk.close()

        return prolog


def obtener_lineas(prolog: Prolog) -> List[str]:
    lineas_unicas = set()
    with _PROLOG_LOCK:
        query = prolog.query("linea(Linea)")
        try:
            for resultado in query:
                linea = str(resultado["Linea"]).strip("'")
                if linea:
                    lineas_unicas.add(linea)
        finally:
            query.close()

    return sorted(lineas_unicas, key=_clave_orden_natural)


def obtener_paradas(prolog: Prolog) -> List[Dict[str, Any]]:
    paradas: Dict[str, Dict[str, Any]] = {}
    with _PROLOG_LOCK:
        query = prolog.query("parada(Id, Calle, Referencia, Latitud, Longitud)")
        try:
            for resultado in query:
                identificador = str(resultado["Id"]).strip("'")
                if identificador in paradas:
                    continue

                calle = str(resultado["Calle"]).strip("'")
                referencia = str(resultado["Referencia"]).strip("'")
                nombre = calle if not referencia or referencia == "none" else f"{calle} - {referencia}"

                paradas[identificador] = {
                    "id": identificador,
                    "nombre": nombre,
                    "latitud": float(resultado["Latitud"]),
                    "longitud": float(resultado["Longitud"]),
                }
        finally:
            query.close()

    return list(paradas.values())


def buscar_rutas(
    prolog: Prolog,
    origen_id: str,
    destino_id: str,
    max_rutas: int = CANTIDAD_ALTERNATIVAS,
) -> List[Dict[str, Any]]:
    origen_seguro = _validar_atomo_prolog(origen_id)
    destino_seguro = _validar_atomo_prolog(destino_id)
    limite = max(1, min(int(max_rutas), 15))

    if origen_seguro == destino_seguro:
        return [{
            "tiempo_total": 0.0,
            "tiempo_viaje": 0.0,
            "tiempo_espera": 0.0,
            "distancia": 0.0,
            "transbordos": 0,
            "lineas": [],
            "paradas": [origen_seguro],
            "etapas": [],
            "transbordos_detalle": [],
        }]

    consulta = f"buscar_rutas_json({origen_seguro}, {destino_seguro}, {limite}, JsonSalida)"
    with _PROLOG_LOCK:
        query = prolog.query(consulta)
        try:
            resultado = next(query, None)
            if not resultado or "JsonSalida" not in resultado:
                return []

            raw_json = str(resultado["JsonSalida"])
            rutas: List[Dict[str, Any]] = json.loads(raw_json)
        finally:
            query.close()

    # Reconstruir detalle de transbordos reales (ignorando transiciones peatonales)
    for ruta in rutas:
        etapas_bus = [e for e in ruta.get("etapas", []) if e.get("linea") != "CAMINANDO"]
        transbordos_detalle = []
        for i in range(len(etapas_bus) - 1):
            transbordos_detalle.append({
                "parada": etapas_bus[i]["destino"],
                "bajar_de": etapas_bus[i]["linea"],
                "subir_a": etapas_bus[i + 1]["linea"],
            })
        ruta["transbordos_detalle"] = transbordos_detalle

    return rutas