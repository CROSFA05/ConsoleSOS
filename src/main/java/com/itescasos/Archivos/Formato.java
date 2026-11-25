package com.itescasos.Archivos;

/**
 * Formato en disco de la imagen (todos los enteros en big-endian):
 *
 *   bloque 0                superbloque (64 bytes usados, ver Superbloque)
 *   bloques 1..b            mapa de bits: 1 bit por bloque de TODA la imagen (bit i = bloque i);
 *                           los bloques de metadatos siempre figuran ocupados
 *   bloques ..              tabla de inodos: 128 bytes por inodo, numerados desde 1
 *   resto                   bloques de datos (archivos y directorios)
 *
 * Inodo (128 bytes):   0 tipo(1) | 1 banderas(1) | 2-3 reservado | 4 idObjeto(4) | 8 tamaño(4)
 *                      12 numBloques(4) | 16..79 hasta 16 punteros directos (4 c/u; 0 = nulo)
 *                      80..127 atributos (48 bytes, los usa seguridad para el cifrado)
 * Entrada de directorio (32 bytes): inodo(4; 0 = ranura libre) | nombre UTF-8 relleno con ceros (28)
 *
 * Asignación: lista de bloques en el inodo (indexada, bloques dispersos). Un archivo o directorio
 * mide como máximo 16 bloques; con bloque de 128 bytes son 2048 bytes.
 */
final class Formato {
    static final int MAGIC = 0x534F5346; // "SOSF"
    static final int VERSION = 1;
    static final int INODO_BYTES = 128;
    static final int ENTRADA_BYTES = 32;
    static final int NOMBRE_MAX = 28;
    static final int MAX_DIRECTOS = 16;
    static final int ATRIBUTOS_BYTES = 48;
    static final int SUPERBLOQUE_BYTES = 64;

    static final int TIPO_LIBRE = 0;
    static final int TIPO_ARCHIVO = 1;
    static final int TIPO_DIR = 2;

    static final int RAIZ = 1;
    static final String LOST_FOUND = "lost+found";

    private Formato() { }

    static int techo(int a, int b) { return (a + b - 1) / b; }

    static boolean bit(byte[] mapa, int i) { return (mapa[i >> 3] & (1 << (i & 7))) != 0; }

    static void ponerBit(byte[] mapa, int i, boolean v) {
        if (v) {
            mapa[i >> 3] |= (byte) (1 << (i & 7));
        } else {
            mapa[i >> 3] &= (byte) ~(1 << (i & 7));
        }
    }
}
