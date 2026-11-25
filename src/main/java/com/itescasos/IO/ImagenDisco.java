package com.itescasos.IO;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Medio persistente: un archivo de tamaño fijo (disk.img) dividido en bloques.
 * Crear una imagen es explícito; abrir una inexistente falla (nunca se crea ni se formatea sola).
 */
public class ImagenDisco implements DispositivoBloques {
    private final RandomAccessFile archivo;
    private final int tamBloque;
    private final int total;
    private final Path ruta;

    private ImagenDisco(Path ruta, RandomAccessFile archivo, int tamBloque, int total) {
        this.ruta = ruta;
        this.archivo = archivo;
        this.tamBloque = tamBloque;
        this.total = total;
    }

    public static ImagenDisco crear(Path ruta, int tamBloque, int totalBloques, boolean sobrescribir) throws IOException {
        if (tamBloque < 1 || totalBloques < 1) {
            throw new IllegalArgumentException("geometría inválida");
        }
        if (Files.exists(ruta) && !sobrescribir) {
            throw new IOException("la imagen ya existe y no se sobrescribe: " + ruta);
        }
        Files.deleteIfExists(ruta);
        RandomAccessFile raf = new RandomAccessFile(ruta.toFile(), "rw");
        raf.setLength((long) tamBloque * totalBloques);
        return new ImagenDisco(ruta, raf, tamBloque, totalBloques);
    }

    public static ImagenDisco abrir(Path ruta, int tamBloque) throws IOException {
        if (!Files.isRegularFile(ruta)) {
            throw new IOException("no existe la imagen: " + ruta);
        }
        long largo = Files.size(ruta);
        if (tamBloque < 1 || largo == 0 || largo % tamBloque != 0) {
            throw new IOException("tamaño de imagen (" + largo + ") no es múltiplo del bloque (" + tamBloque + "): " + ruta);
        }
        return new ImagenDisco(ruta, new RandomAccessFile(ruta.toFile(), "rw"), tamBloque, (int) (largo / tamBloque));
    }

    public Path ruta() { return ruta; }

    @Override
    public int tamanoBloque() { return tamBloque; }

    @Override
    public int totalBloques() { return total; }

    @Override
    public byte[] leerBloque(int n) {
        comprobar(n);
        byte[] b = new byte[tamBloque];
        try {
            archivo.seek((long) n * tamBloque);
            archivo.readFully(b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b;
    }

    @Override
    public void escribirBloque(int n, byte[] datos) {
        comprobar(n);
        if (datos == null || datos.length != tamBloque) {
            throw new IllegalArgumentException("se esperaban " + tamBloque + " bytes");
        }
        try {
            archivo.seek((long) n * tamBloque);
            archivo.write(datos);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void cerrar() {
        try {
            archivo.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void comprobar(int n) {
        if (n < 0 || n >= total) {
            throw new IllegalArgumentException("bloque " + n + " fuera de rango [0," + (total - 1) + "]");
        }
    }
}
