"""
API REST de alto rendimiento con FastAPI para el Sistema de Transporte Público (Bondi).
"""
from contextlib import asynccontextmanager
from typing import List, Dict, Any, Optional
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from config import CANTIDAD_ALTERNATIVAS
from admin_paradas import registrar_admin, serializar_prolog
from servicios_prolog import cargar_prolog, obtener_lineas, obtener_paradas, buscar_rutas


# ======================================================================
# MODELOS PYDANTIC (ESPEJO DE Modelos.kt EN ANDROID)
# ======================================================================

class ParadaDetalle(BaseModel):
    id: str
    nombre: str
    latitud: float
    longitud: float


class EtapaDetalle(BaseModel):
    linea: str
    origen: ParadaDetalle
    destino: ParadaDetalle
    paradas_intermedias: int
    distancia: float
    tiempo: float


class TransbordoDetalle(BaseModel):
    parada: ParadaDetalle
    parada_subida: Optional[ParadaDetalle] = None
    requiere_caminata: bool = False
    metros_caminata: float = 0.0
    bajar_de: str
    subir_a: str
    instruccion: str


class RutaRespuesta(BaseModel):
    es_ruta_exacta: bool = True
    distancia_caminata_final: float = 0.0
    mensaje_usuario: Optional[str] = None
    tiempo_total: float
    tiempo_viaje: float
    tiempo_espera: float
    distancia: float
    transbordos: int
    lineas: List[str]
    etapas: List[EtapaDetalle]
    transbordos_detalle: List[TransbordoDetalle]
    paradas: List[ParadaDetalle]


# ======================================================================
# ESTADO GLOBAL Y CICLO DE VIDA (LIFESPAN)
# ======================================================================

estado_app: Dict[str, Any] = {
    "prolog": None,
    "lineas": [],
    "paradas": [],
    "dict_paradas": {},
    "cache_rutas": {}
}


@asynccontextmanager
async def lifespan(app: FastAPI):
    prolog = cargar_prolog()
    lineas = obtener_lineas(prolog)
    paradas = obtener_paradas(prolog)

    estado_app["prolog"] = prolog
    estado_app["lineas"] = lineas
    estado_app["paradas"] = paradas
    estado_app["dict_paradas"] = {p["id"]: p for p in paradas}
    estado_app["cache_rutas"].clear()
    yield
    estado_app["cache_rutas"].clear()


app = FastAPI(title="Bondi Bariloche API", version="2.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ======================================================================
# ENDPOINTS
# ======================================================================

@app.get("/paradas", response_model=List[ParadaDetalle])
def listar_paradas():
    return estado_app["paradas"]


@app.get("/lineas", response_model=List[str])
def listar_lineas():
    return estado_app["lineas"]


@app.get("/rutas", response_model=List[RutaRespuesta])
@serializar_prolog
def consultar_rutas(
    origen: str = Query(..., description="ID de parada origen, ej: p_18"),
    destino: str = Query(..., description="ID de parada destino, ej: p_413"),
    alternativas: int = Query(CANTIDAD_ALTERNATIVAS, ge=1, le=10)
):
    dict_paradas: Dict[str, Dict[str, Any]] = estado_app["dict_paradas"]

    if origen not in dict_paradas:
        raise HTTPException(status_code=404, detail=f"La parada de origen '{origen}' no existe.")
    if destino not in dict_paradas:
        raise HTTPException(status_code=404, detail=f"La parada de destino '{destino}' no existe.")

    clave_cache = ("v2", origen, destino, alternativas)
    cache = estado_app["cache_rutas"]
    if clave_cache in cache:
        return cache[clave_cache]

    try:
        rutas_crudas = buscar_rutas(estado_app["prolog"], origen, destino, alternativas)
    except ValueError as val_err:
        raise HTTPException(status_code=400, detail=str(val_err))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Error en motor Prolog: {exc}")

    if not rutas_crudas:
        return []

    respuesta_formateada: List[Dict[str, Any]] = []

    for r in rutas_crudas:
        etapas_raw = r.get("etapas", [])
        etapas_expandidas = []

        for e in etapas_raw:
            p_orig = dict_paradas.get(e["origen"])
            p_dest = dict_paradas.get(e["destino"])
            if not p_orig or not p_dest:
                continue
            etapas_expandidas.append({
                "linea": str(e["linea"]),
                "origen": p_orig,
                "destino": p_dest,
                "paradas_intermedias": int(e.get("paradas_intermedias", 1)),
                "distancia": float(e["distancia"]),
                "tiempo": float(e["tiempo"])
            })

        # Construcción de transbordos (directos o con caminata intermedia)
        transbordos_enriquecidos = []
        for idx in range(len(etapas_raw) - 1):
            actual = etapas_raw[idx]
            siguiente = etapas_raw[idx + 1]

            if actual["linea"] == "CAMINANDO":
                continue

            if siguiente["linea"] != "CAMINANDO":
                p_tb = dict_paradas.get(actual["destino"])
                if p_tb:
                    transbordos_enriquecidos.append({
                        "parada": p_tb,
                        "parada_subida": p_tb,
                        "requiere_caminata": False,
                        "metros_caminata": 0.0,
                        "bajar_de": str(actual["linea"]),
                        "subir_a": str(siguiente["linea"]),
                        "instruccion": (
                            f"Descender de Línea {actual['linea']} en '{p_tb['nombre']}' "
                            f"({p_tb['id']}) y esperar la Línea {siguiente['linea']}."
                        )
                    })
            elif idx + 2 < len(etapas_raw):
                colectivo_sig = etapas_raw[idx + 2]
                p_bajada = dict_paradas.get(actual["destino"])
                p_subida = dict_paradas.get(siguiente["destino"])
                metros_cam = round(float(siguiente["distancia"]), 1)

                if p_bajada and p_subida and colectivo_sig["linea"] != "CAMINANDO":
                    transbordos_enriquecidos.append({
                        "parada": p_bajada,
                        "parada_subida": p_subida,
                        "requiere_caminata": True,
                        "metros_caminata": metros_cam,
                        "bajar_de": str(actual["linea"]),
                        "subir_a": str(colectivo_sig["linea"]),
                        "instruccion": (
                            f"Descender de Línea {actual['linea']} en '{p_bajada['nombre']}' ({p_bajada['id']}), "
                            f"caminar {int(metros_cam)} m hasta '{p_subida['nombre']}' ({p_subida['id']}) "
                            f"y abordar la Línea {colectivo_sig['linea']}."
                        )
                    })

        paradas_expandidas = [
            dict_paradas[pid] for pid in r.get("paradas", []) if pid in dict_paradas
        ]

        # Detectar si la última etapa es caminata final
        dist_cam_final = 0.0
        es_exacta = True
        msg_usuario = None
        if etapas_expandidas and etapas_expandidas[-1]["linea"] == "CAMINANDO":
            dist_cam_final = etapas_expandidas[-1]["distancia"]
            es_exacta = False
            msg_usuario = f"Incluye una caminata final de {int(round(dist_cam_final))} m hasta el destino."

        respuesta_formateada.append({
            "es_ruta_exacta": es_exacta,
            "distancia_caminata_final": dist_cam_final,
            "mensaje_usuario": msg_usuario,
            "tiempo_total": float(r["tiempo_total"]),
            "tiempo_viaje": float(r["tiempo_viaje"]),
            "tiempo_espera": float(r["tiempo_espera"]),
            "distancia": float(r["distancia"]),
            "transbordos": int(r["transbordos"]),
            "lineas": [str(l) for l in r.get("lineas", [])],
            "etapas": etapas_expandidas,
            "transbordos_detalle": transbordos_enriquecidos,
            "paradas": paradas_expandidas
        })

    # Solo cachear si hay resultados válidos (Anti-Cache-Poisoning)
    if respuesta_formateada:
        cache[clave_cache] = respuesta_formateada

    return respuesta_formateada


registrar_admin(app, estado_app)
