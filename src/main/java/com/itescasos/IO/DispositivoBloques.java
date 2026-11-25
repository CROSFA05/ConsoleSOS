package com.itescasos.IO;

/** Medio de almacenamiento direccionado por bloques de tamaño fijo. */
public interface DispositivoBloques {
    int tamanoBloque();

    int totalBloques();

    /** Devuelve una copia del bloque n (longitud tamanoBloque()). */
    byte[] leerBloque(int n);

    /** Escribe exactamente tamanoBloque() bytes en el bloque n. */
    void escribirBloque(int n, byte[] datos);

    void cerrar();
}
