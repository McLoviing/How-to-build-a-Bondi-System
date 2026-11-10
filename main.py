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