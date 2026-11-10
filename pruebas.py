"How to build a Bondi System"

from pathlib import Path
from pyswip import Prolog

"Crea el motor"
prolog = Prolog()

"Carga base de conocimiento"
archivo = Path(__file__).with_name("base.pl")
prolog.consult(str(archivo))



"""""
comprueba si las lineas elegidas estan en la base de conocimiento
"""""

lineas_elegidas = ["20", "21", "50", "51", "60", "61", "100"]

for linea in lineas_elegidas:
    resultado = list(prolog.query(f"linea('{linea}')"))

    if resultado:
        print(f"Linea {linea}: encontrada")
    else:
        print(f"Linea {linea}: no encontrada")

"""""
consulta la base de conocimiento para obtener información sobre una parada específica
"""

resultado = list(prolog.query("parada(p_9, Calle, Referencia, Lat, Lon)"))

for parada in resultado:
    print("identificador:", "p_9")
    print("Calle:", parada["Calle"])
    print("Referencia:", parada["Referencia"])
    print("Latitud:", parada["Lat"])
    print("Longitud:", parada["Lon"])


"""""
Busca de conexion
"""
resultado = list(prolog.query("conexion_directa(p_9, p_10, Linea, Distancia, Tiempo)"))

if resultado:
    for conexion in resultado:
        print("Conexión encontrada:")
        print("Linea:", conexion["Linea"])
        print("Distancia:", conexion["Distancia"], "metros")
        print("Tiempo:", conexion["Tiempo"], "minutos")
else:
    print("No se encontró conexión entre")
