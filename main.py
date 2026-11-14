"""
Punto de entrada principal del Sistema de Recomendación de Transporte Público (Bondi).
Coordina la interacción entre la interfaz de usuario y las consultas lógicas a Prolog.
"""
from config import CANTIDAD_ALTERNATIVAS
from servicios_prolog import cargar_prolog, obtener_lineas, obtener_paradas, buscar_rutas
from interfaz import seleccionar_parada, mostrar_ruta


def main():
    print("=" * 66)
    print("      SISTEMA DE RECOMENDACIÓN DE TRANSPORTE PÚBLICO (BONDI)     ")
    print("=" * 66)
    print("Iniciando y cargando base de conocimiento Prolog...")

    try:
        prolog = cargar_prolog()
        lineas = obtener_lineas(prolog)
        paradas = obtener_paradas(prolog)
    except Exception as e:
        print(f"Error al inicializar Prolog: {e}")
        return

    dict_paradas = {p["id"]: p for p in paradas}
    print(f"Base cargada exitosamente: {len(lineas)} líneas disponibles y {len(paradas)} paradas.")
    print("Podés ingresar el nombre de la calle, intersección o el código de parada (ej. 'p_9').")
    print("Escribí 's' o 'salir' en cualquier momento para cancelar.\n")

    while True:
        print("-" * 66)
        origen = seleccionar_parada(paradas, "Ingresá la parada de ORIGEN: ")
        if origen is None:
            print("Operación cancelada. ¡Hasta luego!")
            break

        print(f"-> Origen seleccionado: {origen['nombre']} ({origen['id']})\n")

        destino = seleccionar_parada(paradas, "Ingresá la parada de DESTINO: ")
        if destino is None:
            print("Operación cancelada. ¡Hasta luego!")
            break

        print(f"-> Destino seleccionado: {destino['nombre']} ({destino['id']})\n")

        if origen["id"] == destino["id"]:
            print("El origen y el destino corresponden a la misma parada. No se requiere viaje.")
            continue

        print("Consultando a Prolog por las alternativas más rápidas...")
        print(f" • Origen:  {origen['nombre']} ({origen['id']})")
        print(f" • Destino: {destino['nombre']} ({destino['id']})")
        print("Calculando recorridos en el motor lógico...")

        rutas = buscar_rutas(prolog, origen["id"], destino["id"], CANTIDAD_ALTERNATIVAS)

        if not rutas:
            print("\nNo se encontró ninguna ruta o combinación disponible entre esas paradas.")
        else:
            print(f"\nSe encontraron {len(rutas)} alternativa(s) recomendada(s), ordenadas por rapidez:")
            for indice, ruta in enumerate(rutas, 1):
                mostrar_ruta(ruta, dict_paradas, indice)

        print("\n" + "-" * 66)
        continuar = input("¿Deseás realizar otra consulta? (s/n): ").strip().lower()
        if continuar not in ("s", "si", "sí", "y", "yes"):
            print("¡Gracias por utilizar el sistema de recomendación de colectivos!")
            break


if __name__ == "__main__":
    main()
