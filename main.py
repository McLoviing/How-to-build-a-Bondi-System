"""
Punto de entrada principal (CLI) del Sistema de Recomendación de Transporte Público (Bondi).
Coordina la inicialización del grafo espacial en Prolog, la interacción robusta en consola
y la telemetría de consultas.
"""
import sys
import time
from typing import Dict, List, Any

from config import CANTIDAD_ALTERNATIVAS
from servicios_prolog import cargar_prolog, obtener_lineas, obtener_paradas, buscar_rutas
from interfaz import seleccionar_parada, mostrar_ruta


def inicializar_motor_logico():
    """
    Carga la base de conocimiento, ejecuta la optimización topológica (deduplicación
    de arcos y pre-cálculo de transbordos peatonales via Spatial Hash) y extrae metadatos.
    """
    t_inicio = time.perf_counter()
    prolog = cargar_prolog()

    # Ejecutar inicialización de grafo en Prolog y cerrar el cursor inmediatamente
    q_init = prolog.query("inicializar_grafo.")
    try:
        next(q_init, None)
    finally:
        q_init.close()

    lineas = obtener_lineas(prolog)
    paradas = obtener_paradas(prolog)
    t_fin = time.perf_counter()

    tiempo_ms = (t_fin - t_inicio) * 1000.0
    return prolog, lineas, paradas, tiempo_ms


def main() -> None:
    print("=" * 68)
    print("       SISTEMA DE RECOMENDACIÓN DE TRANSPORTE PÚBLICO (BONDI)       ")
    print("=" * 68)
    print("Iniciando motor lógico SWI-Prolog y construyendo índice espacial...")

    try:
        prolog, lineas, paradas, tiempo_carga_ms = inicializar_motor_logico()
    except FileNotFoundError as exc:
        print(f"\n[ERROR CRÍTICO] Archivo de base de conocimiento no encontrado: {exc}")
        sys.exit(1)
    except Exception as exc:
        print(f"\n[ERROR CRÍTICO] Fallo al inicializar el motor Prolog: {exc}")
        sys.exit(1)

    dict_paradas: Dict[str, Dict[str, Any]] = {p["id"]: p for p in paradas}
    print(f"Sistema listo en {tiempo_carga_ms:.1f} ms: {len(lineas)} líneas y {len(paradas)} paradas indexadas.")
    print("Podés buscar por calle, intersección o código de parada (ej. 'p_9').")
    print("Escribí 's' o 'salir' (o presioná Ctrl+C) para finalizar.\n")

    try:
        while True:
            print("-" * 68)
            origen = seleccionar_parada(paradas, "Ingresá la parada de ORIGEN: ")
            if origen is None:
                print("\nOperación cancelada. ¡Hasta luego!")
                break

            print(f"-> Origen seleccionado:  {origen['nombre']} ({origen['id']})\n")

            destino = seleccionar_parada(paradas, "Ingresá la parada de DESTINO: ")
            if destino is None:
                print("\nOperación cancelada. ¡Hasta luego!")
                break

            print(f"-> Destino seleccionado: {destino['nombre']} ({destino['id']})\n")

            if origen["id"] == destino["id"]:
                print("El origen y el destino corresponden a la misma parada. No se requiere viaje.")
                continue

            print(f"Calculando rutas óptimas ({origen['id']} -> {destino['id']})...")

            t0 = time.perf_counter()
            try:
                rutas = buscar_rutas(prolog, origen["id"], destino["id"], CANTIDAD_ALTERNATIVAS)
            except ValueError as val_err:
                print(f"[AVISO] Entrada inválida: {val_err}")
                continue
            except Exception as query_err:
                print(f"[ERROR] Fallo durante la inferencia en Prolog: {query_err}")
                continue
            latencia_ms = (time.perf_counter() - t0) * 1000.0

            if not rutas:
                print(f"\nNo se encontró ninguna ruta o combinación disponible (consulta en {latencia_ms:.2f} ms).")
            else:
                print(
                    f"\nSe encontraron {len(rutas)} alternativa(s) recomendada(s) "
                    f"(resuelto en {latencia_ms:.2f} ms):"
                )
                for indice, ruta in enumerate(rutas, 1):
                    mostrar_ruta(ruta, dict_paradas, indice)

            print("\n" + "-" * 68)
            continuar = input("¿Deseás realizar otra consulta? (s/n): ").strip().lower()
            if continuar not in ("s", "si", "sí", "y", "yes", ""):
                print("¡Gracias por utilizar el sistema de recomendación de colectivos!")
                break

    except (KeyboardInterrupt, EOFError):
        print("\n\nInterrupción detectada. Cerrando sesión de Bondi System...")
        sys.exit(0)


if __name__ == "__main__":
    main()