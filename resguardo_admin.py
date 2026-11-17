"""
Capa de Resguardo, Auditoría, Ring Buffer de Backups y Control de Acceso por Token Único.
Fuerza la inmutabilidad de base.pl a nivel de sistema operativo (S_IREAD)
y gestiona las mutaciones sobre overlay_admin.pl.
"""
import hashlib
import hmac
import json
import logging
import os
import re
import shutil
import stat
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from fastapi import HTTPException
from config import ARCHIVO_PROLOG

logger = logging.getLogger(__name__)

__all__ = [
    "BASE_DIR",
    "ARCHIVO_BASE_INMUTABLE",
    "ARCHIVO_OVERLAY",
    "DIR_BACKUPS",
    "ARCHIVO_AUDITORIA",
    "autorizar_admin",
    "obtener_archivo_trabajo",
    "escribir_atomico",
    "crear_snapshot_backup",
    "registrar_auditoria",
    "listar_historial_backups",
    "restaurar_snapshot",
    "resetear_a_fabrica",
]

BASE_DIR: Path = Path(ARCHIVO_PROLOG).resolve().parent
ARCHIVO_BASE_INMUTABLE: Path = BASE_DIR / "base.pl"
ARCHIVO_OVERLAY: Path = BASE_DIR / "overlay_admin.pl"
ARCHIVO_META: Path = BASE_DIR / "overlay_admin.meta"
DIR_BACKUPS: Path = BASE_DIR / "backups_admin"
ARCHIVO_AUDITORIA: Path = BASE_DIR / "auditoria_admin.jsonl"

MAX_SNAPSHOTS_RING_BUFFER = 30
BUCKET_CAPACIDAD = 5.0
BUCKET_RECARGA_POR_SEG = 0.2

_estado_bucket: Tuple[float, float] = (BUCKET_CAPACIDAD, 0.0)


def _hash_blake2b(ruta: Path) -> str:
    """Calcula huella criptográfica rápida BLAKE2b-128 en bloque único."""
    return hashlib.blake2b(ruta.read_bytes(), digest_size=16).hexdigest()


def _blindar_base_inmutable() -> None:
    """Marca base.pl como solo lectura en el SO para impedir sobrescrituras accidentales."""
    if ARCHIVO_BASE_INMUTABLE.is_file():
        try:
            os.chmod(ARCHIVO_BASE_INMUTABLE, stat.S_IREAD | stat.S_IRGRP | stat.S_IROTH)
        except OSError:
            pass


def _verificar_rate_limit() -> None:
    global _estado_bucket
    ahora = time.monotonic()
    tokens, ultimo_ts = _estado_bucket
    tokens = min(BUCKET_CAPACIDAD, tokens + (ahora - ultimo_ts) * BUCKET_RECARGA_POR_SEG)

    if tokens < 1.0:
        _estado_bucket = (tokens, ahora)
        raise HTTPException(429, "Demasiados intentos de autenticación. Esperá unos segundos.")

    _estado_bucket = (tokens - 1.0, ahora)


def autorizar_admin(token_recibido: str) -> None:
    global _estado_bucket
    _verificar_rate_limit()

    esperado = os.environ.get("BONDI_ADMIN_TOKEN", "").strip()
    if not esperado:
        raise HTTPException(503, "Administración deshabilitada: configurar BONDI_ADMIN_TOKEN")

    if not token_recibido or not hmac.compare_digest(
        token_recibido.encode("utf-8"), esperado.encode("utf-8")
    ):
        raise HTTPException(403, "Clave de administrador incorrecta")

    _estado_bucket = (BUCKET_CAPACIDAD, time.monotonic())


def obtener_archivo_trabajo() -> Path:
    """
    Devuelve overlay_admin.pl (garantizando que sea escribible) e impone solo-lectura
    sobre base.pl. Si overlay_admin.pl no existe, lo clona desde base.pl.
    """
    if not ARCHIVO_BASE_INMUTABLE.is_file():
        raise FileNotFoundError(f"No existe la base inmutable: {ARCHIVO_BASE_INMUTABLE}")

    _blindar_base_inmutable()
    hash_actual_base = _hash_blake2b(ARCHIVO_BASE_INMUTABLE)

    if not ARCHIVO_OVERLAY.is_file():
        shutil.copyfile(ARCHIVO_BASE_INMUTABLE, ARCHIVO_OVERLAY)
        os.chmod(ARCHIVO_OVERLAY, stat.S_IREAD | stat.S_IWRITE)
        ARCHIVO_META.write_text(hash_actual_base, encoding="utf-8")
    elif ARCHIVO_META.is_file():
        hash_origen = ARCHIVO_META.read_text(encoding="utf-8").strip()
        if hash_origen and hash_origen != hash_actual_base:
            logger.warning(
                "Aviso: base.pl cambió externamente desde que se creó overlay_admin.pl. "
                "Usá /admin/restaurar-fabrica si querés descartar el overlay y adoptar el nuevo base.pl."
            )

    return ARCHIVO_OVERLAY


def escribir_atomico(ruta: Path, contenido: bytes) -> None:
    """Escritura atómica con guardia explícita contra mutación de base.pl."""
    if ruta.resolve() == ARCHIVO_BASE_INMUTABLE.resolve():
        raise PermissionError("Violación de seguridad: intento de escritura directa sobre base.pl bloqueado.")

    with tempfile.NamedTemporaryFile(dir=ruta.parent, delete=False) as temporal:
        nombre = Path(temporal.name)
        try:
            temporal.write(contenido)
            temporal.flush()
            os.fsync(temporal.fileno())
        except Exception:
            temporal.close()
            nombre.unlink(missing_ok=True)
            raise

    try:
        for intento in range(3):
            try:
                os.replace(nombre, ruta)
                return
            except PermissionError:
                if intento == 2:
                    raise
                time.sleep(0.015 * (3 ** intento))
    finally:
        nombre.unlink(missing_ok=True)


def crear_snapshot_backup(accion: str) -> Path:
    DIR_BACKUPS.mkdir(parents=True, exist_ok=True)
    actual = obtener_archivo_trabajo()

    accion_limpia = re.sub(r"[^a-zA-Z0-9_-]", "_", accion)[:32]
    ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
    destino = DIR_BACKUPS / f"snap_{ts}_{accion_limpia}.pl"
    shutil.copyfile(actual, destino)

    snapshots = sorted(DIR_BACKUPS.glob("snap_*.pl"))
    excedente = len(snapshots) - MAX_SNAPSHOTS_RING_BUFFER
    for viejo in snapshots[:max(0, excedente)]:
        viejo.unlink(missing_ok=True)

    return destino


def registrar_auditoria(accion: str, detalle: dict) -> None:
    evento = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "accion": accion,
        "detalle": detalle,
    }
    with open(ARCHIVO_AUDITORIA, "a", encoding="utf-8") as f:
        f.write(json.dumps(evento, ensure_ascii=False) + "\n")


def listar_historial_backups(limite: int = 15) -> List[Dict[str, str]]:
    if not DIR_BACKUPS.is_dir():
        return []
    snapshots = sorted(DIR_BACKUPS.glob("snap_*.pl"), reverse=True)[:limite]
    resultado = []
    for s in snapshots:
        partes = s.stem.split("_", 4)
        resultado.append({
            "archivo": s.name,
            "fecha_utc": f"{partes[1]} {partes[2]}" if len(partes) >= 3 else s.stem,
            "accion": partes[4] if len(partes) >= 5 else "mutacion",
        })
    return resultado


def restaurar_snapshot(nombre_archivo: Optional[str] = None) -> str:
    if not DIR_BACKUPS.is_dir():
        raise HTTPException(404, "No hay historial de respaldos disponible")

    if nombre_archivo:
        if "/" in nombre_archivo or "\\" in nombre_archivo or not nombre_archivo.startswith("snap_"):
            raise HTTPException(400, "Nombre de respaldo inválido")
        objetivo = DIR_BACKUPS / nombre_archivo
    else:
        snapshots = sorted(DIR_BACKUPS.glob("snap_*.pl"))
        if not snapshots:
            raise HTTPException(404, "No hay respaldos previos para deshacer")
        objetivo = snapshots[-1]

    if not objetivo.is_file():
        raise HTTPException(404, "El archivo de respaldo solicitado no existe")

    escribir_atomico(ARCHIVO_OVERLAY, objetivo.read_bytes())
    return objetivo.name


def resetear_a_fabrica() -> None:
    if ARCHIVO_OVERLAY.is_file():
        crear_snapshot_backup("antes_de_reset_fabrica")
        ARCHIVO_OVERLAY.unlink(missing_ok=True)
    ARCHIVO_META.unlink(missing_ok=True)