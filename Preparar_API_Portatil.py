"""Ejecutar con el Python del entorno .venv-win en la PC donde funciona Bondi."""
import os
import shutil
import subprocess
import sys
import sysconfig
from pathlib import Path

raiz = Path(__file__).resolve().parent
salida = raiz / 'Bondi_API_Portatil'
python = Path(sys.base_prefix)
prolog = Path(os.environ.get('SWI_HOME_DIR') or r'C:\Program Files\swipl')
if sys.platform != 'win32' or sys.prefix == sys.base_prefix:
    raise SystemExit('Ejecutar desde Windows con .venv-win activado.')
if not (python / 'python.exe').is_file() or not (prolog / 'bin' / 'libswipl.dll').is_file():
    raise SystemExit('No se encontro Python o SWI-Prolog. Revisar las rutas.')
if salida.exists():
    raise SystemExit('Ya existe Bondi_API_Portatil. Renombrala antes de generar otra copia.')
salida.mkdir()
# Copiar instalacion real de Python (no el venv, que contiene rutas absolutas).
shutil.copytree(python, salida / 'python', ignore=shutil.ignore_patterns('__pycache__', '*.pyc', 'site-packages', 'Scripts', 'tcl', 'Doc', 'test', 'tests'))
shutil.copytree(sysconfig.get_path('purelib'), salida / 'python' / 'Lib' / 'site-packages', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
shutil.copytree(prolog, salida / 'prolog')
# Estado de fabrica: no se copian datos de prueba ni historial local.
for nombre in ('api.py','config.py','servicios_prolog.py','admin_paradas.py','admin_lineas.py','resguardo_admin.py','base.pl','transporte.json','Iniciar_API.bat'):
    shutil.copyfile(raiz / nombre, salida / nombre)
# Validacion inicial con el runtime copiado y sin Python instalado en PATH.
env = os.environ.copy()
for nombre in ('PYTHONHOME','PYTHONPATH','VIRTUAL_ENV','__PYVENV_LAUNCHER__'):
    env.pop(nombre, None)
env.update(SWI_HOME_DIR=str(salida / 'prolog'), LIBSWIPL_PATH=str(salida / 'prolog' / 'bin' / 'libswipl.dll'), PYTHONNOUSERSITE='1')
env['PATH'] = str(salida / 'prolog' / 'bin') + os.pathsep + str(salida / 'python') + os.pathsep + os.path.join(os.environ['SystemRoot'], 'System32')
subprocess.run([str(salida / 'python' / 'python.exe'), '-c', 'import uvicorn, api; from servicios_prolog import cargar_prolog, obtener_lineas; p = cargar_prolog(); print(obtener_lineas(p))'], cwd=salida, env=env, check=True)
print('Carpeta creada:', salida)
print('Copiala al pendrive y ejecuta Iniciar_API.bat. Proba en otra PC antes de la defensa.')
