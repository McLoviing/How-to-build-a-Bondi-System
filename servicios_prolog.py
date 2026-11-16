"""
Servicio de comunicación y consultas con el motor de Prolog (SWI-Prolog / PySwip).
Implementa:
- Inyección automática de DLLs de SWI-Prolog en Windows.
- Exclusión mutua (RLock) para seguridad multihilo en FastAPI.
- Inicialización topológica automática (deduplicación + Spatial Hash peatonal).
- Validación Fail-Fast de predicados y sanitización estricta anti-inyección.
- Puente JSON nativo (buscar_rutas_json/4) de cero-parsing.
"""
import json
import logging
import os
import re
import sys
import threading
from pathlib import Path
from typing import List, Dict, Any

# Garantizar resolución de libswipl.dll en Windows antes de importar pyswip
if sys.platform == "win32":
    _ruta_swipl_bin = Path(r"C:\Program Files\swipl\bin")
    if _ruta_swipl_bin.is_dir():
        os.environ["PATH"] = f"{_ruta_swipl_bin};{os.environ.get('PATH', '')}"
        if hasattr(os, "add_dll_directory"):
            os.add_dll_directory(str(_ruta_swipl_bin))

from pyswip import Prolog
from config import ARCHIVO_PROLOG, CANTIDAD_ALTERNATIVAS

logger = logging.getLogger(__name__)

# Candado reentrante para proteger la frontera C-Python de PySwip frente a peticiones concurrentes
_PROLOG_LOCK = threading.RLock()

_PATRON_ATOMO_SEGURO = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
_PATRON_NATURAL_SORT = re.compile(r"(\d+)")


def _validar_atomo_prolog(atomo: str) -> str:
    """Sanitiza identificadores para impedir inyección de código en SWI-Prolog."""
    if not isinstance(atomo, str) or not _PATRON_ATOMO_SEGURO.match(atomo):
        raise ValueError(f"Identificador inválido o inseguro para Prolog: '{atomo}'")
    return atomo


def _clave_orden_natural(texto: str) -> list:
    """Ordenamiento natural homogéneo para líneas mixtas ('2', '10', '51A')."""
    partes = _PATRON_NATURAL_SORT.split(texto.lower())
    return [
        (0, int(parte)) if parte.isdigit() else (1, parte)
        for parte in partes if parte
    ]


def _verificar_contrato_prolog(prolog: Prolog, ruta_archivo: Path) -> None:
    """Patrón Fail-Fast: verifica que base.pl exponga todos los predicados requeridos."""
    predicados_requeridos = [
        "inicializar_grafo/0",
        "linea/1",
        "parada/5",
        "tramo/5",
        "buscar_rutas_json/4"
    ]
    for pred in predicados_requeridos:
        q = prolog.query(f"current_predicate({pred})")
        try:
            if next(q, None) is None:
                raise RuntimeError(
                    f"Contrato roto con Prolog: falta el predicado '{pred}' en {ruta_archivo}"
                )
        finally:
            q.close()


def cargar_prolog(ruta_archivo: Path = ARCHIVO_PROLOG) -> Prolog:
    """
    Carga la base de conocimiento, verifica contratos, deduplica tramos
    y precalcula los transbordos peatonales mediante indexación espacial O(V).
    """
    ruta_resuelta = ruta_archivo.resolve()
    if not ruta_resuelta.is_file():
        raise FileNotFoundError(f"No se encontró la base de conocimiento Prolog en: {ruta_resuelta}")

    with _PROLOG_LOCK:
        prolog = Prolog()
        prolog.consult(ruta_resuelta.as_posix())

        _verificar_contrato_prolog(prolog, ruta_resuelta)

        # Ejecutar inicialización topológica del grafo multimodal
        q_init = prolog.query("inicializar_grafo.")
        try:
            if next(q_init, None) is None:
                raise RuntimeError("Fallo al ejecutar inicializar_grafo/0 en SWI-Prolog.")
        finally:
            q_init.close()

        # Confirmar que los tramos peatonales quedaron assertados en memoria
        q_walk = prolog.query("once(tramo('CAMINANDO', _, _, _, _))")
        try:
            if next(q_walk, None) is None:
                logger.warning("Advertencia: No se generaron arcos 'CAMINANDO' en memoria.")
        finally:
            q_walk.close()

        return prolog


def obtener_lineas(prolog: Prolog) -> List[str]:
    """Obtiene todas las líneas únicas registradas en Prolog ordenadas naturalmente."""
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
    """Extrae los hechos parada/5 deduplicados por identificador."""
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
                    "longitud": float(resultado["Longitud"])
                }
        finally:
            query.close()

    return list(paradas.values())


def _construir_transbordos_detalle(etapas: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Analiza la secuencia de etapas para extraer combinaciones directas en parada
    y combinaciones con caminata intermedia entre paradas cercanas.
    """
    detalle = []
    for idx in range(len(etapas) - 1):
        actual = etapas[idx]
        siguiente = etapas[idx + 1]

        if str(actual.get("linea")).upper() == "CAMINANDO":
            continue

        if str(siguiente.get("linea")).upper() != "CAMINANDO":
            detalle.append({
                "parada": actual["destino"],
                "parada_subida": actual["destino"],
                "requiere_caminata": False,
                "metros_caminata": 0.0,
                "bajar_de": str(actual["linea"]),
                "subir_a": str(siguiente["linea"])
            })
        elif idx + 2 < len(etapas):
            colectivo_sig = etapas[idx + 2]
            if str(colectivo_sig.get("linea")).upper() != "CAMINANDO":
                detalle.append({
                    "parada": actual["destino"],
                    "parada_subida": siguiente["destino"],
                    "requiere_caminata": True,
                    "metros_caminata": round(float(siguiente.get("distancia", 0.0)), 1),
                    "bajar_de": str(actual["linea"]),
                    "subir_a": str(colectivo_sig["linea"])
                })
    return detalle


def buscar_rutas(
    prolog: Prolog,
    origen_id: str,
    destino_id: str,
    max_rutas: int = CANTIDAD_ALTERNATIVAS
) -> List[Dict[str, Any]]:
    """
    Consulta las rutas óptimas multimodales a través del puente JSON de SWI-Prolog
    bajo exclusión mutua thread-safe.
    """
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
            "transbordos_detalle": []
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
        except Exception as exc:
            logger.exception(f"Fallo en consulta Prolog ({origen_seguro} -> {destino_seguro})")
            raise RuntimeError(f"Error ejecutando motor lógico: {exc}") from exc
        finally:
            query.close()

    for ruta in rutas:
        ruta["transbordos_detalle"] = _construir_transbordos_detalle(ruta.get("etapas", []))

    return rutas