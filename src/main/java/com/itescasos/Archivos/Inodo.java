package com.itescasos.Archivos;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Descriptor persistente de un objeto (archivo o directorio). */
final class Inodo {
    int numero;
    int tipo = Formato.TIPO_LIBRE;
    int flags;
    int idObjeto;
    int tamano;
    int numBloques;
    int[] bloques = new int[Formato.MAX_DIRECTOS];
    byte[] atributos = new byte[Formato.ATRIBUTOS_BYTES];

    static Inodo vacio(int numero) {
        Inodo i = new Inodo();
        i.numero = numero;
        return i;
    }

    boolean enUso() { return tipo != Formato.TIPO_LIBRE; }

    boolean esDir() { return tipo == Formato.TIPO_DIR; }

    static Inodo leer(int numero, byte[] buf, int off) {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        Inodo i = new Inodo();
        i.numero = numero;
        i.tipo = buf[off] & 0xFF;
        i.flags = buf[off + 1] & 0xFF;
        i.idObjeto = bb.getInt(off + 4);
        i.tamano = bb.getInt(off + 8);
        i.numBloques = bb.getInt(off + 12);
        for (int k = 0; k < Formato.MAX_DIRECTOS; k++) {
            i.bloques[k] = bb.getInt(off + 16 + 4 * k);
        }
        System.arraycopy(buf, off + 80, i.atributos, 0, Formato.ATRIBUTOS_BYTES);
        return i;
    }

    void escribir(byte[] buf, int off) {
        Arrays.fill(buf, off, off + Formato.INODO_BYTES, (byte) 0);
        if (tipo == Formato.TIPO_LIBRE) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(buf);
        buf[off] = (byte) tipo;
        buf[off + 1] = (byte) flags;
        bb.putInt(off + 4, idObjeto);
        bb.putInt(off + 8, tamano);
        bb.putInt(off + 12, numBloques);
        for (int k = 0; k < Formato.MAX_DIRECTOS; k++) {
            bb.putInt(off + 16 + 4 * k, bloques[k]);
        }
        System.arraycopy(atributos, 0, buf, off + 80, Formato.ATRIBUTOS_BYTES);
    }

    /** Bloques realmente asignados (los primeros numBloques punteros). */
    int[] bloquesUsados() {
        return Arrays.copyOf(bloques, Math.max(0, Math.min(numBloques, Formato.MAX_DIRECTOS)));
    }
}
