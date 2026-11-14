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


def mostrar_ruta(ruta: dict, dict_paradas: dict, indice: int):
    """
    Imprime de forma ordenada y comprensible los detalles de una ruta recomendada.
    """
    lineas = ruta.get("lineas", [])
    lineas_str = " -> ".join(f"Línea {l}" for l in lineas) if lineas else "Misma parada"
    tipo = "Directo (sin transbordo)" if ruta["transbordos"] == 0 else f"{ruta['transbordos']} transbordo(s)"

    print(f"\n==================================================================")
    print(f" OPCIÓN {indice}: {lineas_str} [{tipo}]")
    print(f"==================================================================")
    print(f" • Tiempo total estimado: {ruta['tiempo_total']:.1f} min")
    print(f"   - Tiempo en viaje: {ruta['tiempo_viaje']:.1f} min")
    if ruta["transbordos"] > 0:
        print(f"   - Tiempo estimado de espera por transbordo: {ruta['tiempo_espera']:.1f} min")
    print(f" • Distancia total: {ruta['distancia'] / 1000:.2f} km ({ruta['distancia']:.0f} metros)")
    print(f" • Total de transbordos: {ruta['transbordos']}")
    print(f" • Cantidad de paradas del recorrido: {len(ruta['paradas'])}")
    print(f"\n Itinerario:")

    paradas = ruta["paradas"]
    if len(paradas) <= 1:
        print("   Origen y destino coinciden.")
        return

    orig_id = paradas[0]
    dest_id = paradas[-1]
    nom_orig = dict_paradas.get(orig_id, {}).get("nombre", orig_id)
    nom_dest = dict_paradas.get(dest_id, {}).get("nombre", dest_id)

    etapas = ruta.get("etapas", [])
    if etapas:
        for i, etapa in enumerate(etapas, 1):
            nom_o = dict_paradas.get(etapa["origen"], {}).get("nombre", etapa["origen"])
            nom_d = dict_paradas.get(etapa["destino"], {}).get("nombre", etapa["destino"])

            print(f"   {i}. Tomar Línea {etapa['linea']} en parada: {nom_o} ({etapa['origen']})")
            print(f"      Viajar {etapa['paradas_intermedias']} parada(s) (~{etapa['tiempo']:.1f} min, {etapa['distancia']:.0f} m)")

            if i < len(etapas):
                prox_linea = etapas[i]["linea"]
                print(f"      Bajar en parada de combinación: {nom_d} ({etapa['destino']})")
                print(f"\n      >>> TRANSBORDO:")
                print(f"      >>> Descender del colectivo en: {nom_d} ({etapa['destino']})")
                print(f"      >>> Esperar y abordar la Línea {prox_linea} [Espera aprox: {ESPERA_POR_TRANSBORDO:.0f} min]\n")
            else:
                print(f"      Bajar en destino final: {nom_d} ({etapa['destino']})")
    else:
        # Fallback si no viniesen etapas estructuradas
        orig_id = paradas[0]
        dest_id = paradas[-1]
        nom_orig = dict_paradas.get(orig_id, {}).get("nombre", orig_id)
        nom_dest = dict_paradas.get(dest_id, {}).get("nombre", dest_id)
        print(f"   1. Viajar desde {nom_orig} ({orig_id}) hasta {nom_dest} ({dest_id}) en Líneas: {lineas_str}")

