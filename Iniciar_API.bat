@echo off
setlocal
cd /d "%~dp0"
set "PYTHONHOME="
set "PYTHONPATH="
set "VIRTUAL_ENV="
set "PYTHONNOUSERSITE=1"
set "SWI_HOME_DIR=%~dp0prolog"
set "LIBSWIPL_PATH=%~dp0prolog\bin\libswipl.dll"
set "PATH=%~dp0prolog\bin;%~dp0python;%SystemRoot%\System32;%SystemRoot%"
set "BONDI_ADMIN_TOKEN="
set /p "BONDI_ADMIN_TOKEN=Clave de administrador (vacio deshabilita admin): "
ipconfig
"%~dp0python\python.exe" -m uvicorn api:app --host 0.0.0.0 --port 8000
pause
