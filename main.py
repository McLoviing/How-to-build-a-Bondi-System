

from pathlib import Path
import unicodedata
from pyswip import Prolog

# Busca el archivo base.pl en la misma carpeta donde está main.py.
ARCHIVO_PROLOG = Path(__file__).with_name("base.pl")

# Número de recorridos alternativos que se muestran para el criterio elegido.
CANTIDAD_ALTERNATIVAS = 5

# Tiempo que se suma por cada cambio de línea.
# Es una estimación para contemplar la espera; no representa un horario oficial.
ESPERA_POR_TRANSBORDO = 8.0

def normalizar(texto):

    texto = unicodedata.normalize("NFD", texto.lower().strip())
    return "".join(
        caracter for caracter in texto
        if unicodedata.category(caracter) != "Mn"
    )

def cargar_prolog():
    """
    ***
    * Carga el archivo base.pl en el motor de Prolog.
    *
    * PRE: base.pl debe estar en la misma carpeta que main.py.
    * POST: la base queda disponible para realizar consultas.
    * @return: objeto Prolog.
    """
    if not ARCHIVO_PROLOG.exists():
        raise FileNotFoundError(f"No se encontró {ARCHIVO_PROLOG.name}")

    prolog = Prolog()
    prolog.consult(str(ARCHIVO_PROLOG))
    return prolog


def obtener_lineas(prolog):
    """
    ***
    * Obtiene todas las líneas declaradas en Prolog.
    *
    * PRE: Prolog debe estar cargado.
    * POST: devuelve los identificadores de línea sin repetir.
    * @return: lista de líneas.
    """
    lineas = []

    for resultado in prolog.query("linea(Linea)"):
        linea = str(resultado["Linea"])
        if linea not in lineas:
            lineas.append(linea)

    return sorted(lineas)

def seleccionar_parada(paradas, mensaje):
    """
    ***
    * Permite elegir una parada por número, nombre o identificador.
    *
    * PRE: paradas debe contener los datos obtenidos desde Prolog.
    * POST: devuelve la parada elegida o None si el usuario sale.
    * @param paradas: lista de paradas disponibles.
    * @param mensaje: mensaje que se muestra al usuario.
    * @return: parada seleccionada o None.
    """
    seleccionada = None
    terminar = False

    while seleccionada is None and not terminar:
        entrada = input(mensaje).strip()

        if entrada.lower() in ("salir", "s"):
            terminar = True
        elif entrada.isdigit():
            numero = int(entrada) - 1
            if 0 <= numero < len(paradas):
                seleccionada = paradas[numero]
            else:
                print("El número ingresado no es válido.")
        else:
            buscado = normalizar(entrada)

            coincidencias = [
                parada for parada in paradas
                if buscado and buscado in normalizar(parada["nombre"])
            ]

            if not coincidencias:
                coincidencias = [
                    parada for parada in paradas
                    if entrada.lower() == parada["id"].lower()
                ]

            if len(coincidencias) == 1:
                seleccionada = coincidencias[0]
            elif len(coincidencias) > 1:
                print("Se encontraron varias paradas:")
                for numero, parada in enumerate(coincidencias[:20], 1):
                    print(f"{numero:2}. {parada['nombre']} ({parada['id']})")

                opcion = input(
                    "Elegí un número o escribí otra búsqueda: "
                ).strip()
                if opcion.isdigit():
                    numero = int(opcion) - 1
                    if 0 <= numero < min(20, len(coincidencias)):
                        seleccionada = coincidencias[numero]
            else:
                print("No se encontró esa parada. Probá nuevamente.")

    return seleccionada

def cargar_grafo(prolog):
    """
    ***
    * Consulta los tramos de Prolog y arma un grafo de conexiones.
    *
    * PRE: la base debe contener hechos tramo/5.
    * POST: cada parada queda relacionada con sus tramos de salida.
    * @return: diccionario con los tramos agrupados por origen.
    """
    grafo = {}

    consulta = "tramo(Linea, Origen, Destino, Distancia, Tiempo)"

    for resultado in prolog.query(consulta):
        origen = str(resultado["Origen"])

        tramo = {
            "linea": str(resultado["Linea"]),
            "destino": str(resultado["Destino"]),
            "distancia": float(resultado["Distancia"]),
            "tiempo": float(resultado["Tiempo"])
        }

        if origen not in grafo:
            grafo[origen] = []

        grafo[origen].append(tramo)

    return grafo