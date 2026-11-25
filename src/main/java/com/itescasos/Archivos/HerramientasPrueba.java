package com.itescasos.Archivos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.itescasos.IO.ImagenDisco;

/**
 * Corrupción DOCUMENTADA para demostrar fsck: apaga el bit de un bloque de datos que sigue
 * referenciado, sin cambiar los bytes del bloque. Solo opera sobre una COPIA (nunca sobre el origen).
 */
public final class HerramientasPrueba {
    private HerramientasPrueba() { }

    /** Copia 'origen' en 'copia', apaga el bit de 'bloque' y devuelve el registro de la alteración. */
    public static String apagarBitDeBloque(Path origen, Path copia, int bloque) throws IOException {
        if (origen.toAbsolutePath().normalize().equals(copia.toAbsolutePath().normalize())) {
            throw new IOException("se rechaza corromper el origen: indique una copia distinta");
        }
        Files.copy(origen, copia, StandardCopyOption.REPLACE_EXISTING);
        int b = Superbloque.tamBloqueDe(copia);
        ImagenDisco img = ImagenDisco.abrir(copia, b);
        try {
            Superbloque sb = Superbloque.leer(img.leerBloque(0));
            if (bloque < sb.inicioDatos || bloque >= sb.totalBloques) {
                throw new IOException("el bloque " + bloque + " no es de datos (datos: " + sb.inicioDatos + ".." + (sb.totalBloques - 1) + ")");
            }
            int bloqueMapa = sb.inicioBitmap + (bloque / 8) / b;
            int byteEnBloque = (bloque / 8) % b;
            int bit = bloque % 8;
            byte[] m = img.leerBloque(bloqueMapa);
            if ((m[byteEnBloque] & (1 << bit)) == 0) {
                throw new IOException("el bloque " + bloque + " ya figura libre: elija un bloque ocupado");
            }
            byte antes = m[byteEnBloque];
            m[byteEnBloque] &= (byte) ~(1 << bit);
            img.escribirBloque(bloqueMapa, m);
            long offset = (long) bloqueMapa * b + byteEnBloque;
            return String.format("Copia: %s | bloque de datos %d -> bit %d del byte %d del bloque de mapa %d (offset %d en la imagen) | byte antes=0x%02X despues=0x%02X | contenido del bloque sin cambios",
                    copia, bloque, bit, byteEnBloque, bloqueMapa, offset, antes & 0xFF, m[byteEnBloque] & 0xFF);
        } finally {
            img.cerrar();
        }
    }
}
