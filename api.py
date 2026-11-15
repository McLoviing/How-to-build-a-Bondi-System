"""
API REST para el Sistema de Transporte Público (Bondi System).
Expone endpoints HTTP para ser consumidos por una aplicación móvil (Flutter, React Native, Kotlin, Swift, etc.).
"""
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional
import threading
import math

from config import CANTIDAD_ALTERNATIVAS
from servicios_prolog import cargar_prolog, obtener_lineas, obtener_paradas, buscar_rutas

app = FastAPI(
    title="Bondi System API",
    description="API REST para recomendación de líneas de transporte público con Prolog",
    version="1.0.0"
)

# Habilitar CORS para que aplicaciones móviles y navegadores puedan conectarse
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # En producción se pueden restringir los dominios permitidos
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Lock de concurrencia: SWI-Prolog a través de PySwip comparte un único motor en C.
# El lock garantiza thread-safety ante múltiples peticiones simultáneas desde dispositivos móviles.
prolog_lock = threading.Lock()

# Cache en memoria de paradas y líneas para respuestas ultra rápidas
prolog = None
paradas_cache = []
dict_paradas = {}
lineas_cache = []


# =====================================================================
# MODELOS DE DATOS (PYDANTIC) PARA RESPUESTAS JSON
# =====================================================================

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
    bajar_de: str
    subir_a: str
    instruccion: str


class RutaRespuesta(BaseModel):
    tiempo_total: float
    tiempo_viaje: float
    tiempo_espera: float
    distancia: float
    transbordos: int
    lineas: List[str]
    etapas: List[EtapaDetalle]
    transbordos_detalle: List[TransbordoDetalle]
    paradas: List[ParadaDetalle]


# =====================================================================
# INICIALIZACIÓN
# =====================================================================

@app.on_event("startup")
def inicializar_servicio():
    global prolog, paradas_cache, dict_paradas, lineas_cache
    print("[API] Inicializando motor de Prolog y cargando base de conocimiento...")
    with prolog_lock:
        prolog = cargar_prolog()
        lineas_cache = obtener_lineas(prolog)
        paradas_cache = obtener_paradas(prolog)
        dict_paradas = {p["id"]: p for p in paradas_cache}
    print(f"[API] Listo: {len(lineas_cache)} líneas y {len(paradas_cache)} paradas cargadas.")


# =====================================================================
# ENDPOINTS
# =====================================================================

@app.get("/", tags=["General"])
def estado_api():
    """Estado general y metadatos del servicio."""
    return {
        "sistema": "Bondi System API",
        "estado": "operativo",
        "total_lineas": len(lineas_cache),
        "total_paradas": len(paradas_cache),
        "documentacion": "/docs"
    }


@app.get("/lineas", response_model=List[str], tags=["Transporte"])
def listar_lineas():
    """Devuelve la lista de todas las líneas de transporte público disponibles."""
    return lineas_cache


@app.get("/paradas", response_model=List[ParadaDetalle], tags=["Transporte"])
def listar_paradas(
    buscar: Optional[str] = Query(None, description="Filtra por nombre de calle, intersección o código (ej. 'Moreno' o 'p_9')")
):
    """
    Lista paradas disponibles. Permite autocompletado en buscadores móviles.
    """
    if not buscar:
        return paradas_cache

    termino = buscar.lower().strip()
    coincidencias = [
        p for p in paradas_cache
        if termino in p["nombre"].lower() or termino == p["id"].lower()
    ]
    return coincidencias


@app.get("/paradas/cercanas", response_model=List[ParadaDetalle], tags=["Transporte"])
def paradas_cercanas(
    lat: float = Query(..., description="Latitud GPS del usuario"),
    lon: float = Query(..., description="Longitud GPS del usuario"),
    limite: int = Query(5, description="Cantidad máxima de paradas a devolver")
):
    """
    Devuelve las paradas más próximas a las coordenadas GPS del móvil (usando distancia Haversine).
    Ideal para botones tipo 'Usar mi ubicación actual'.
    """
    def distancia_km(lat1, lon1, lat2, lon2):
        r = 6371.0  # Radio medio de la Tierra en km
        dlat = math.radians(lat2 - lat1)
        dlon = math.radians(lon2 - lon1)
        a = math.sin(dlat / 2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2)**2
        return 2 * r * math.asin(math.sqrt(a))

    paradas_con_dist = [
        (distancia_km(lat, lon, p["latitud"], p["longitud"]), p)
        for p in paradas_cache
    ]
    paradas_con_dist.sort(key=lambda x: x[0])
    return [p for _, p in paradas_con_dist[:limite]]


@app.get("/rutas", response_model=List[RutaRespuesta], tags=["Recomendaciones"])
def recomendar_rutas(
    origen: str = Query(..., description="ID de la parada de inicio (ej. 'p_9')"),
    destino: str = Query(..., description="ID de la parada de destino (ej. 'p_20')"),
    alternativas: int = Query(CANTIDAD_ALTERNATIVAS, ge=1, le=10, description="Cantidad de alternativas deseadas")
):
    """
    Calcula y devuelve las rutas más rápidas entre dos paradas usando el motor de Prolog.
    Cada ruta incluye el desglose de tiempo, distancia, líneas a tomar y la lista completa
    de paradas con coordenadas para poder dibujarlas en un mapa móvil (Google Maps / Mapbox).
    """
    if origen not in dict_paradas:
        raise HTTPException(status_code=404, detail=f"Parada de origen '{origen}' no encontrada.")
    if destino not in dict_paradas:
        raise HTTPException(status_code=404, detail=f"Parada de destino '{destino}' no encontrada.")

    with prolog_lock:
        rutas_crudas = buscar_rutas(prolog, origen, destino, alternativas)

    rutas_formateadas = []
    for r in rutas_crudas:
        # Enriquecer cada parada con su nombre y coordenadas GPS para el frontend móvil
        paradas_completas = [
            dict_paradas[pid] for pid in r["paradas"]
            if pid in dict_paradas
        ]

        # Enriquecer etapas con datos completos de parada origen y destino
        etapas_enriquecidas = []
        for e in r.get("etapas", []):
            p_orig = dict_paradas.get(e["origen"])
            p_dest = dict_paradas.get(e["destino"])
            if p_orig and p_dest:
                etapas_enriquecidas.append({
                    "linea": e["linea"],
                    "origen": p_orig,
                    "destino": p_dest,
                    "paradas_intermedias": e["paradas_intermedias"],
                    "distancia": e["distancia"],
                    "tiempo": e["tiempo"]
                })

        # Enriquecer los transbordos con detalles específicos para el usuario móvil
        transbordos_enriquecidos = []
        for tb in r.get("transbordos_detalle", []):
            p_tb = dict_paradas.get(tb["parada"])
            if p_tb:
                transbordos_enriquecidos.append({
                    "parada": p_tb,
                    "bajar_de": tb["bajar_de"],
                    "subir_a": tb["subir_a"],
                    "instruccion": (
                        f"Descender de Línea {tb['bajar_de']} en '{p_tb['nombre']}' "
                        f"({p_tb['id']}) y esperar la Línea {tb['subir_a']}."
                    )
                })

        rutas_formateadas.append({
            "tiempo_total": r["tiempo_total"],
            "tiempo_viaje": r["tiempo_viaje"],
            "tiempo_espera": r["tiempo_espera"],
            "distancia": r["distancia"],
            "transbordos": r["transbordos"],
            "lineas": r["lineas"],
            "etapas": etapas_enriquecidas,
            "transbordos_detalle": transbordos_enriquecidos,
            "paradas": paradas_completas
        })

    return rutas_formateadas


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("api:app", host="0.0.0.0", port=8000, reload=True)


