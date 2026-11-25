package com.itescasos.Archivos;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Utilidades puras sobre el contenido de un bloque de directorio. */
final class Directorio {
    private Directorio() { }

    static List<EntradaDir> entradas(byte[] bloque, int bloqueLogico) {
        List<EntradaDir> out = new ArrayList<EntradaDir>();
        ByteBuffer bb = ByteBuffer.wrap(bloque);
        for (int r = 0; r < bloque.length / Formato.ENTRADA_BYTES; r++) {
            int ino = bb.getInt(r * Formato.ENTRADA_BYTES);
            if (ino != 0) {
                out.add(new EntradaDir(nombreEn(bloque, r), ino, bloqueLogico, r));
            }
        }
        return out;
    }

    static String nombreEn(byte[] bloque, int ranura) {
        int off = ranura * Formato.ENTRADA_BYTES + 4;
        int len = 0;
        while (len < Formato.NOMBRE_MAX && bloque[off + len] != 0) {
            len++;
        }
        return new String(bloque, off, len, StandardCharsets.UTF_8);
    }

    static int ranuraLibre(byte[] bloque) {
        ByteBuffer bb = ByteBuffer.wrap(bloque);
        for (int r = 0; r < bloque.length / Formato.ENTRADA_BYTES; r++) {
            if (bb.getInt(r * Formato.ENTRADA_BYTES) == 0) {
                return r;
            }
        }
        return -1;
    }

    static void poner(byte[] bloque, int ranura, String nombre, int inodo) {
        int off = ranura * Formato.ENTRADA_BYTES;
        Arrays.fill(bloque, off, off + Formato.ENTRADA_BYTES, (byte) 0);
        ByteBuffer.wrap(bloque).putInt(off, inodo);
        byte[] n = nombre.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, bloque, off + 4, n.length);
    }

    static void borrar(byte[] bloque, int ranura) {
        int off = ranura * Formato.ENTRADA_BYTES;
        Arrays.fill(bloque, off, off + Formato.ENTRADA_BYTES, (byte) 0);
    }

    /** Nombre local válido: 1..28 bytes UTF-8, sin '/', NUL ni caracteres de control, y distinto de '.' y '..'. */
    static boolean nombreValido(String n) {
        if (n == null || n.isEmpty() || n.equals(".") || n.equals("..")) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c < 32 || c == 127) {
                return false;
            }
        }
        return n.getBytes(StandardCharsets.UTF_8).length <= Formato.NOMBRE_MAX;
    }
}
