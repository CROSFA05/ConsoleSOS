package com.itescasos.IO;

/** Medio en memoria (para pruebas de planificación y de sistema de archivos sin imagen). */
public class MemoriaBloques implements DispositivoBloques {
    private final byte[][] bloques;
    private final int tamBloque;

    public MemoriaBloques(int tamBloque, int totalBloques) {
        if (tamBloque < 1 || totalBloques < 1) {
            throw new IllegalArgumentException("geometría inválida");
        }
        this.tamBloque = tamBloque;
        this.bloques = new byte[totalBloques][tamBloque];
    }

    @Override
    public int tamanoBloque() { return tamBloque; }

    @Override
    public int totalBloques() { return bloques.length; }

    @Override
    public byte[] leerBloque(int n) {
        comprobar(n);
        return bloques[n].clone();
    }

    @Override
    public void escribirBloque(int n, byte[] datos) {
        comprobar(n);
        if (datos == null || datos.length != tamBloque) {
            throw new IllegalArgumentException("se esperaban " + tamBloque + " bytes");
        }
        bloques[n] = datos.clone();
    }

    @Override
    public void cerrar() { }

    private void comprobar(int n) {
        if (n < 0 || n >= bloques.length) {
            throw new IllegalArgumentException("bloque " + n + " fuera de rango [0," + (bloques.length - 1) + "]");
        }
    }
}
