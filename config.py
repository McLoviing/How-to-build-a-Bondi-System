"""
Configuración general del sistema de transporte público.
"""
from pathlib import Path

# Ruta al archivo de la base de conocimiento de Prolog
ARCHIVO_PROLOG = Path(__file__).with_name("base.pl")

# Cantidad máxima de alternativas de recorrido que se muestran
CANTIDAD_ALTERNATIVAS = 5

# Tiempo estimado (en minutos) que se suma por cada transbordo
ESPERA_POR_TRANSBORDO = 8.0

