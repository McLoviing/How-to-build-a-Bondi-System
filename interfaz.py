"""
Módulo de interfaz de usuario en consola.
Gestiona la selección interactiva de paradas y la visualización formateada de las alternativas recomendadas.
"""
import unicodedata
from config import ESPERA_POR_TRANSBORDO


def normalizar(texto: str) -> str:
    """
    Normaliza texto quitando acentos y espacios adicionales para búsquedas flexibles.
    """
    texto = unicodedata.normalize("NFD", texto.lower().strip())
    return "".join(
        caracter for caracter in texto
        if unicodedata.category(caracter) != "Mn"
    )


def seleccionar_parada(paradas: list, mensaje: str):
    """
    Permite elegir una parada por número de lista, nombre, calle o código (ej. 'p_9').
    
    PRE: paradas es una lista de diccionarios con las paradas disponibles.
    POST: Devuelve el diccionario de la parada elegida o None si el usuario cancela.
    """
    seleccionada = None
    terminar = False

    while seleccionada is None and not terminar:
        entrada = input(mensaje).strip()

        if entrada.lower() in ("salir", "s"):
            return None

        # Selección directa por índice numérico
        if entrada.isdigit():
            numero = int(entrada) - 1
            if 0 <= numero < len(paradas):
                return paradas[numero]
            print("El número ingresado no es válido.")
            continue

        # Coincidencia exacta por identificador (ej. 'p_9')
        coincidencia_id = [
            p for p in paradas
            if entrada.lower() == p["id"].lower()
        ]
        if coincidencia_id:
            return coincidencia_id[0]

        buscado = normalizar(entrada)
        if not buscado:
            print("Por favor, ingresá un nombre o código de parada válido.")
            continue

        coincidencias = [
            p for p in paradas
            if buscado in normalizar(p["nombre"])
        ]

        if len(coincidencias) == 1:
            return coincidencias[0]
        elif len(coincidencias) > 1:
            limite = min(20, len(coincidencias))
            print(f"\nSe encontraron {len(coincidencias)} paradas (mostrando hasta {limite}):")
            for num, p in enumerate(coincidencias[:limite], 1):
                print(f"{num:2}. {p['nombre']} ({p['id']})")

            while seleccionada is None:
                opcion = input(
                    "Elegí un número de la lista (o escribí otra búsqueda / 's' para salir): "
                ).strip()
                if opcion.lower() in ("salir", "s"):
                    return None
                elif opcion.isdigit():
                    num = int(opcion) - 1
                    if 0 <= num < limite:
                        return coincidencias[num]
                    print("El número ingresado no está en el rango mostrado.")
                else:
                    buscado_nuevo = normalizar(opcion)
                    coincidencias_nuevas = [
                        p for p in paradas
                        if buscado_nuevo and buscado_nuevo in normalizar(p["nombre"])
                    ]
                    if coincidencias_nuevas:
                        coincidencias = coincidencias_nuevas
                        limite = min(20, len(coincidencias))
                        print(f"\nSe encontraron {len(coincidencias)} paradas (mostrando hasta {limite}):")
                        for num, p in enumerate(coincidencias[:limite], 1):
                            print(f"{num:2}. {p['nombre']} ({p['id']})")
                    else:
                        print("No se encontraron paradas con ese término. Probá nuevamente.")
        else:
            print("No se encontró esa parada. Probá nuevamente.")

    return seleccionada


def _formatear_nombre_parada(parada_id: str, dict_paradas: dict) -> str:
    """Devuelve 'CALLE - REFERENCIA (p_id)' de forma segura."""
    info = dict_paradas.get(parada_id)
    if not info:
        return f"Parada desconocida ({parada_id})"
    return f"{info['nombre']} ({parada_id})"


def _es_caminata(etapa: dict) -> bool:
    return str(etapa.get("linea", "")).upper() == "CAMINANDO"


def mostrar_ruta(ruta: dict, dict_paradas: dict, indice: int) -> None:
    """
    Renderiza en consola una ruta multimodal (Colectivo + Caminata) con lenguaje
    natural claro, diferenciando transbordos en parada, transbordos a pie y tramo final.
    """
    tiempo_total = ruta["tiempo_total"]
    tiempo_viaje = ruta["tiempo_viaje"]
    tiempo_espera = ruta.get("tiempo_espera", round(tiempo_total - tiempo_viaje, 1))
    distancia_m = int(round(ruta["distancia"]))
    distancia_km = distancia_m / 1000.0
    transbordos = ruta["transbordos"]
    etapas = ruta.get("etapas", [])
    paradas = ruta.get("paradas", [])

    # Resumen de líneas reales (excluir 'CAMINANDO' de la cabecera de líneas)
    lineas_bus = [l for l in ruta.get("lineas", []) if str(l).upper() != "CAMINANDO"]
    resumen_lineas = " -> ".join(f"Línea {l}" for l in lineas_bus) if lineas_bus else "Solo caminata"

    print("\n" + "=" * 68)
    print(f" OPCIÓN #{indice}: {resumen_lineas}")
    print("=" * 68)
    print(f" • Tiempo total estimado: {tiempo_total:.1f} min")
    print(f"   - Tiempo de desplazamiento (bus/pie): {tiempo_viaje:.1f} min")
    if tiempo_espera > 0:
        print(f"   - Tiempo estimado de espera por transbordo: {tiempo_espera:.1f} min")
    print(f" • Distancia total: {distancia_km:.2f} km ({distancia_m} metros)")
    print(f" • Total de transbordos: {transbordos}")
    print(f" • Cantidad de paradas del recorrido: {len(paradas)}")

    if not etapas:
        return

    print("\n Itinerario paso a paso:")
    total_etapas = len(etapas)

    for idx, etapa in enumerate(etapas):
        paso = idx + 1
        orig_txt = _formatear_nombre_parada(etapa["origen"], dict_paradas)
        dest_txt = _formatear_nombre_parada(etapa["destino"], dict_paradas)
        dist_etapa = int(round(etapa["distancia"]))
        tiempo_etapa = etapa["tiempo"]
        es_ultima = (idx == total_etapas - 1)
        siguiente_etapa = etapas[idx + 1] if not es_ultima else None

        if _es_caminata(etapa):
            # --- RENDERIZADO DE TRAMO PEATONAL ---
            if es_ultima:
                print(f"\n   {paso}. [A PIE] Caminar hasta destino final:")
                print(f"      Desde: {orig_txt}")
                print(f"      Hasta: {dest_txt} ({dist_etapa} m, ~{tiempo_etapa:.1f} min)")
            elif idx == 0:
                sig_linea = siguiente_etapa["linea"]
                print(f"\n   {paso}. [A PIE] Caminar hacia parada de inicio:")
                print(f"      Desde: {orig_txt}")
                print(f"      Hasta: {dest_txt} ({dist_etapa} m, ~{tiempo_etapa:.1f} min) para abordar Línea {sig_linea}")
            else:
                sig_linea = siguiente_etapa["linea"]
                print(f"\n   {paso}. [A PIE] Caminar para combinar de línea:")
                print(f"      Desde: {orig_txt}")
                print(f"      Hasta: {dest_txt} ({dist_etapa} m, ~{tiempo_etapa:.1f} min)")
                print(f"\n      >>> COMBINACIÓN:")
                print(f"      >>> Esperar y abordar la Línea {sig_linea} en {dest_txt} [Espera aprox: 8.0 min]")
        else:
            # --- RENDERIZADO DE TRAMO EN COLECTIVO ---
            linea = etapa["linea"]
            cant_paradas = etapa["paradas_intermedias"]

            print(f"\n   {paso}. [COLECTIVO] Tomar Línea {linea} en: {orig_txt}")
            print(f"      Viajar {cant_paradas} parada(s) (~{tiempo_etapa:.1f} min, {dist_etapa} m)")

            if es_ultima:
                print(f"      Descender en destino final: {dest_txt}")
            elif _es_caminata(siguiente_etapa):
                # El tramo siguiente es a pie: el bloque siguiente explicará la caminata
                print(f"      Descender en: {dest_txt} para continuar a pie")
            else:
                # Transbordo directo en la misma parada física entre dos colectivos
                sig_linea = siguiente_etapa["linea"]
                print(f"      Descender en parada de combinación: {dest_txt}")
                print(f"\n      >>> TRANSBORDO EN PARADA:")
                print(f"      >>> Permanecer en {dest_txt}")
                print(f"      >>> Esperar y abordar la Línea {sig_linea} [Espera aprox: 8.0 min]")