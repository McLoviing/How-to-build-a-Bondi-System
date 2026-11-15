"""
Servicio de comunicación y consultas con el motor de Prolog (SWI-Prolog / PySwip).
Delega en Prolog todo el procesamiento lógico y la búsqueda de caminos en el grafo.
"""
import re
from pathlib import Path
from pyswip import Prolog
from config import ARCHIVO_PROLOG, CANTIDAD_ALTERNATIVAS


def cargar_prolog(ruta_archivo: Path = ARCHIVO_PROLOG) -> Prolog:
    """
    Carga el archivo base.pl en el motor de Prolog.
    
    PRE: base.pl debe existir en la ruta indicada.
    POST: base queda consultada y lista para resolver consultas lógicas.
    """
    if not ruta_archivo.exists():
        raise FileNotFoundError(f"No se encontró el archivo: {ruta_archivo}")

    prolog = Prolog()
    # Se usa as_posix() para evitar conflictos con barras invertidas en Windows
    prolog.consult(ruta_archivo.resolve().as_posix())
    return prolog


def obtener_lineas(prolog: Prolog) -> list:
    """
    Consulta las líneas de transporte disponibles en Prolog.
    PRE: Prolog inicializado.
    POST: Devuelve la lista ordenada de identificadores de línea.
    """
    lineas = []
    for resultado in prolog.query("linea(Linea)"):
        linea = str(resultado["Linea"])
        if linea not in lineas:
            lineas.append(linea)

    return sorted(lineas, key=lambda x: int(x) if x.isdigit() else x)


def obtener_paradas(prolog: Prolog) -> list:
    """
    Lee las paradas declaradas como hechos parada/5 en Prolog.
    PRE: La base de Prolog debe contener los hechos parada/5.
    POST: Devuelve una lista de diccionarios con id, nombre y coordenadas.
    """
    paradas = {}
    consulta = "parada(Id, Calle, Referencia, Latitud, Longitud)"

    for resultado in prolog.query(consulta):
        identificador = str(resultado["Id"])
        calle = str(resultado["Calle"])
        referencia = str(resultado["Referencia"])

        nombre = calle if not referencia or referencia == "''" else f"{calle} - {referencia}"

        paradas[identificador] = {
            "id": identificador,
            "nombre": nombre,
            "latitud": float(resultado["Latitud"]),
            "longitud": float(resultado["Longitud"])
        }

    return list(paradas.values())


# PySwip (>= 0.3) devuelve los términos compuestos como texto, por ejemplo:
#   "etapa(41, p_379, p_217, 4, 3306.8, 11.1)"
_PATRON_ETAPA = re.compile(r"^etapa\((.*)\)$", re.DOTALL)


def _procesar_etapa_prolog(etapa_obj) -> dict:
    """Extrae los datos de un término etapa(...) retornado por Prolog.

    Acepta tres representaciones, según la versión de PySwip:
    objeto con .args, lista/tupla, o cadena de texto "etapa(...)".
    """
    if hasattr(etapa_obj, "args"):
        l, o, d, cant, dist, t = etapa_obj.args
    elif isinstance(etapa_obj, (list, tuple)) and len(etapa_obj) >= 6:
        l, o, d, cant, dist, t = etapa_obj[:6]
    elif isinstance(etapa_obj, str):
        coincidencia = _PATRON_ETAPA.match(etapa_obj.strip())
        if not coincidencia:
            return {}
        # Los átomos (línea, paradas) y los números no contienen comas.
        partes = [x.strip().strip("'") for x in coincidencia.group(1).split(",")]
        if len(partes) != 6:
            return {}
        l, o, d, cant, dist, t = partes
    else:
        return {}

    return {
        "linea": str(l),
        "origen": str(o),
        "destino": str(d),
        "paradas_intermedias": int(cant),
        "distancia": float(dist),
        "tiempo": float(t)
    }


def buscar_rutas(prolog: Prolog, origen_id: str, destino_id: str, max_rutas: int = CANTIDAD_ALTERNATIVAS) -> list:
    """
    Ejecuta la búsqueda de rutas óptimas directamente en Prolog.
    Todo el trabajo pesado de recorrido del grafo, evaluación de transbordos
    y cálculo de tiempos se realiza en el motor de inferencia de Prolog.

    PRE: origen_id y destino_id deben ser átomos válidos en Prolog (ej. 'p_9').
    POST: Devuelve hasta max_rutas alternativas con métricas, etapas y paradas de transbordo exactas.
    """
    if origen_id == destino_id:
        return [{
            "tiempo_total": 0.0,
            "tiempo_viaje": 0.0,
            "tiempo_espera": 0.0,
            "distancia": 0.0,
            "transbordos": 0,
            "lineas": [],
            "paradas": [origen_id],
            "etapas": [],
            "transbordos_detalle": []
        }]

    consulta = (
        f"buscar_ruta({origen_id}, {destino_id}, "
        "TiempoTotal, TiempoViaje, Transbordos, DistTotal, Lineas, Paradas, Etapas)"
    )

    rutas = []
    for res in prolog.query(consulta):
        tiempo_total = float(res["TiempoTotal"])
        tiempo_viaje = float(res["TiempoViaje"])
        transbordos = int(res["Transbordos"])
        distancia = float(res["DistTotal"])

        # PySwip devuelve los átomos como objetos o cadenas
        lineas = [str(linea) for linea in res["Lineas"]]
        paradas = [str(parada) for parada in res["Paradas"]]

        # Procesar etapas calculadas por Prolog
        etapas = []
        if "Etapas" in res and res["Etapas"]:
            for e in res["Etapas"]:
                e_dict = _procesar_etapa_prolog(e)
                if e_dict:
                    etapas.append(e_dict)

        # Si por compatibilidad no hubiese etapas pero sí paradas y 1 línea:
        if not etapas and len(lineas) == 1 and len(paradas) >= 2:
            etapas = [{
                "linea": lineas[0],
                "origen": paradas[0],
                "destino": paradas[-1],
                "paradas_intermedias": len(paradas) - 1,
                "distancia": distancia,
                "tiempo": tiempo_viaje
            }]

        # Identificar exactamente las paradas de transbordo (dónde bajar y dónde subir)
        transbordos_detalle = []
        for i in range(len(etapas) - 1):
            parada_transbordo = etapas[i]["destino"]
            transbordos_detalle.append({
                "parada": parada_transbordo,
                "bajar_de": etapas[i]["linea"],
                "subir_a": etapas[i + 1]["linea"]
            })

        rutas.append({
            "tiempo_total": tiempo_total,
            "tiempo_viaje": tiempo_viaje,
            "tiempo_espera": round(tiempo_total - tiempo_viaje, 2),
            "distancia": distancia,
            "transbordos": transbordos,
            "lineas": lineas,
            "paradas": paradas,
            "etapas": etapas,
            "transbordos_detalle": transbordos_detalle
        })

        if len(rutas) >= max_rutas:
            break

    return rutas
