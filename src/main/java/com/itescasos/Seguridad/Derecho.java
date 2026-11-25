package com.itescasos.Seguridad;

/**
 * Derechos que se pueden conceder sobre un objeto (archivo, directorio o dispositivo).
 * Coincide con el uso que hace SistemaArchivos (LEER / ESCRIBIR).
 */
public enum Derecho {
    LEER,
    ESCRIBIR,
    EJECUTAR;

    /** Interpreta "r", "w", "x", "rw", "rwx", etc. (minúsculas o mayúsculas). */
    public static int mascaraDesdeCadena(String s) {
        if (s == null || s.isEmpty()) return 0;
        int m = 0;
        String t = s.toLowerCase();
        if (t.indexOf('r') >= 0) m |= 1;
        if (t.indexOf('w') >= 0) m |= 2;
        if (t.indexOf('x') >= 0) m |= 4;
        return m;
    }

    public int bit() {
        switch (this) {
            case LEER:     return 1;
            case ESCRIBIR: return 2;
            case EJECUTAR: return 4;
            default:       return 0;
        }
    }
}
