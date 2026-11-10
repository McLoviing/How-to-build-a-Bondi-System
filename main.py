

from pathlib import Path
from pyswip import Prolog

# Busca el archivo base.pl en la misma carpeta donde está main.py.
ARCHIVO_PROLOG = Path(__file__).with_name("base.pl")

# Número de recorridos alternativos que se muestran para el criterio elegido.
CANTIDAD_ALTERNATIVAS = 5

# Tiempo que se suma por cada cambio de línea.
# Es una estimación para contemplar la espera; no representa un horario oficial.
ESPERA_POR_TRANSBORDO = 8.0


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