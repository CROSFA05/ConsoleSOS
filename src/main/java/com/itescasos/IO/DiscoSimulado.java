package com.itescasos.IO;

import java.nio.file.Path;

import com.itescasos.Core.Reloj;

/**
 * Pila de E/S lista para el sistema de archivos: medio (imagen) + driver de disco +
 * driver de terminal + administrador + adaptador por bloques. Todo comparte un solo reloj.
 */
public class DiscoSimulado {
    public static final String DISCO = "disco";
    public static final String TERMINAL = "terminal";

    private final DispositivoBloques medio;
    private final DriverDisco driverDisco;
    private final DriverTerminal driverTerminal;
    private final AdministradorES administrador;
    private final DiscoES bloques;

    public DiscoSimulado(DispositivoBloques medio, Reloj reloj, PoliticaDisco politica,
                         int bloquesPorCilindro, int cilindrosPorTick) {
        this.medio = medio;
        this.driverDisco = new DriverDisco(DISCO, medio, bloquesPorCilindro, politica, 0,
                Direccion.ASCENDENTE, cilindrosPorTick);
        this.driverTerminal = new DriverTerminal(TERMINAL, 16);
        this.administrador = new AdministradorES(reloj);
        administrador.registrar(driverDisco);
        administrador.registrar(driverTerminal);
        this.bloques = new DiscoES(administrador, DISCO, medio);
    }

    public static DiscoSimulado abrirImagen(Path ruta, int tamBloque, Reloj reloj, PoliticaDisco politica,
                                            int bloquesPorCilindro, int cilindrosPorTick) throws java.io.IOException {
        return new DiscoSimulado(ImagenDisco.abrir(ruta, tamBloque), reloj, politica, bloquesPorCilindro, cilindrosPorTick);
    }

    /** Dispositivo de bloques que el sistema de archivos debe usar (pasa por el driver). */
    public DiscoES bloques() { return bloques; }

    public DispositivoBloques medio() { return medio; }
    public DriverDisco disco() { return driverDisco; }
    public DriverTerminal terminal() { return driverTerminal; }
    public AdministradorES administrador() { return administrador; }

    public void cerrar() { medio.cerrar(); }
}
