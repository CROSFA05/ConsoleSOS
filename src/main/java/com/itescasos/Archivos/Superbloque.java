package com.itescasos.Archivos;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32;

/** Superbloque: firma, versión, geometría y contadores. Lleva un CRC32 para detectar daño. */
final class Superbloque {
    int version = Formato.VERSION;
    int tamBloque;
    int totalBloques;
    int totalInodos;
    int inicioBitmap;
    int bloquesBitmap;
    int inicioInodos;
    int bloquesInodos;
    int inicioDatos;
    int inodoRaiz = Formato.RAIZ;
    int proximoId = 1;
    int bloquesLibres;
    int inodosLibres;
    /** 1 = desmontado limpiamente, 0 = montado o interrumpido. */
    int estado = 1;

    static Superbloque calcular(int tamBloque, int totalBloques, int totalInodos) {
        if (tamBloque < Formato.INODO_BYTES || tamBloque % Formato.INODO_BYTES != 0) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA,
                    "el bloque debe ser múltiplo de " + Formato.INODO_BYTES + " bytes y al menos " + Formato.INODO_BYTES);
        }
        if (totalInodos < 2) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA, "se requieren al menos 2 inodos (raíz y lost+found)");
        }
        Superbloque s = new Superbloque();
        s.tamBloque = tamBloque;
        s.totalBloques = totalBloques;
        s.totalInodos = totalInodos;
        s.inicioBitmap = 1;
        s.bloquesBitmap = Formato.techo(Formato.techo(totalBloques, 8), tamBloque);
        s.inicioInodos = s.inicioBitmap + s.bloquesBitmap;
        s.bloquesInodos = Formato.techo(totalInodos, tamBloque / Formato.INODO_BYTES);
        s.inicioDatos = s.inicioInodos + s.bloquesInodos;
        if (s.inicioDatos + 4 > totalBloques) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA,
                    "imagen demasiado pequeña: los metadatos ocupan " + s.inicioDatos + " de " + totalBloques + " bloques");
        }
        s.bloquesLibres = totalBloques - s.inicioDatos;
        s.inodosLibres = totalInodos;
        return s;
    }

    /** Lee un superbloque de al menos 64 bytes; falla con IMAGEN_INVALIDA si la firma o el CRC no cuadran. */
    static Superbloque leer(byte[] b) {
        if (b.length < Formato.SUPERBLOQUE_BYTES) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA, "superbloque truncado");
        }
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (bb.getInt(0) != Formato.MAGIC) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA,
                    "la firma no corresponde a una imagen SOS (no se reformatea automáticamente)");
        }
        CRC32 crc = new CRC32();
        crc.update(b, 0, 60);
        if ((int) crc.getValue() != bb.getInt(60)) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA,
                    "el superbloque está dañado (CRC no coincide); restaure el respaldo de la imagen");
        }
        Superbloque s = new Superbloque();
        s.version = bb.getInt(4);
        s.tamBloque = bb.getInt(8);
        s.totalBloques = bb.getInt(12);
        s.totalInodos = bb.getInt(16);
        s.inicioBitmap = bb.getInt(20);
        s.bloquesBitmap = bb.getInt(24);
        s.inicioInodos = bb.getInt(28);
        s.bloquesInodos = bb.getInt(32);
        s.inicioDatos = bb.getInt(36);
        s.inodoRaiz = bb.getInt(40);
        s.proximoId = bb.getInt(44);
        s.bloquesLibres = bb.getInt(48);
        s.inodosLibres = bb.getInt(52);
        s.estado = bb.getInt(56);
        if (s.version != Formato.VERSION) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA, "versión de formato no soportada: " + s.version);
        }
        return s;
    }

    byte[] aBytes() {
        ByteBuffer bb = ByteBuffer.allocate(tamBloque);
        bb.putInt(0, Formato.MAGIC);
        bb.putInt(4, version);
        bb.putInt(8, tamBloque);
        bb.putInt(12, totalBloques);
        bb.putInt(16, totalInodos);
        bb.putInt(20, inicioBitmap);
        bb.putInt(24, bloquesBitmap);
        bb.putInt(28, inicioInodos);
        bb.putInt(32, bloquesInodos);
        bb.putInt(36, inicioDatos);
        bb.putInt(40, inodoRaiz);
        bb.putInt(44, proximoId);
        bb.putInt(48, bloquesLibres);
        bb.putInt(52, inodosLibres);
        bb.putInt(56, estado);
        CRC32 crc = new CRC32();
        crc.update(bb.array(), 0, 60);
        bb.putInt(60, (int) crc.getValue());
        return bb.array();
    }

    /** null si la geometría guardada coincide con la que se recalcula; si no, el motivo. */
    String validarGeometria(int tamBloqueDispositivo, int totalBloquesDispositivo) {
        if (tamBloque != tamBloqueDispositivo) {
            return "el tamaño de bloque del superbloque (" + tamBloque + ") no coincide con el del dispositivo (" + tamBloqueDispositivo + ")";
        }
        if (totalBloques != totalBloquesDispositivo) {
            return "el superbloque declara " + totalBloques + " bloques y el dispositivo tiene " + totalBloquesDispositivo;
        }
        Superbloque e;
        try {
            e = calcular(tamBloque, totalBloques, totalInodos);
        } catch (ErrorFS ex) {
            return ex.getMessage();
        }
        if (e.inicioBitmap != inicioBitmap || e.bloquesBitmap != bloquesBitmap || e.inicioInodos != inicioInodos
                || e.bloquesInodos != bloquesInodos || e.inicioDatos != inicioDatos || inodoRaiz != Formato.RAIZ) {
            return "las regiones del superbloque no son coherentes con la geometría (solapamiento o desbordamiento)";
        }
        return null;
    }

    /** Lee solo el encabezado de una imagen para conocer su tamaño de bloque antes de abrirla. */
    static int tamBloqueDe(Path imagen) throws IOException {
        byte[] cab = new byte[Formato.SUPERBLOQUE_BYTES];
        try (RandomAccessFile raf = new RandomAccessFile(imagen.toFile(), "r")) {
            raf.readFully(cab);
        } catch (java.io.EOFException e) {
            throw new ErrorFS(ErrorFS.Codigo.IMAGEN_INVALIDA, "la imagen es demasiado corta para tener superbloque");
        }
        return leer(cab).tamBloque;
    }
}
