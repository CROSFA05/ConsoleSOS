package com.itescasos.Seguridad;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Cifrado simple XOR con clave (requisito mínimo de la guía).
 * La clave se deriva a 48 bytes y se puede guardar en los atributos del inodo
 * (Formato.ATRIBUTOS_BYTES = 48) junto con el flag FLAG_CIFRADO.
 *
 * No es criptografía fuerte: es un mecanismo educativo para demostrar
 * que el contenido no se puede leer sin la clave correcta.
 */
public final class Cifrado {

    private Cifrado() { }

    /** Expande o recorta la clave a exactamente 48 bytes (relleno con hash simple). */
    public static byte[] derivarClave(String clave) {
        if (clave == null) clave = "";
        byte[] src = clave.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[48];
        for (int i = 0; i < 48; i++) {
            out[i] = (byte) (src[i % Math.max(1, src.length)] ^ (i * 31 + 17));
        }
        // Mezcla adicional para evitar claves débiles
        for (int i = 1; i < 48; i++) {
            out[i] ^= out[i - 1];
        }
        return out;
    }

    /** Aplica XOR con la clave derivada (reversible: cifrar = descifrar). */
    public static byte[] aplicar(byte[] datos, String clave) {
        if (datos == null) return new byte[0];
        byte[] k = derivarClave(clave);
        byte[] r = Arrays.copyOf(datos, datos.length);
        for (int i = 0; i < r.length; i++) {
            r[i] ^= k[i % k.length];
        }
        return r;
    }

    /** Verifica si los atributos guardados coinciden con la clave (comprobación simple). */
    public static boolean claveCoincide(byte[] atributosGuardados, String clave) {
        if (atributosGuardados == null || atributosGuardados.length < 48) return false;
        byte[] esperada = derivarClave(clave);
        // Guardamos una "huella" de la clave en los atributos (no la clave en claro)
        byte[] huella = huella(esperada);
        for (int i = 0; i < 16; i++) {
            if (atributosGuardados[i] != huella[i]) return false;
        }
        return true;
    }

    /** Genera los 48 bytes de atributos a guardar cuando se cifra un archivo. */
    public static byte[] atributosParaClave(String clave) {
        byte[] k = derivarClave(clave);
        byte[] attrs = new byte[48];
        byte[] h = huella(k);
        System.arraycopy(h, 0, attrs, 0, 16);
        // Resto: material seudoaleatorio derivado (no la clave misma)
        for (int i = 16; i < 48; i++) {
            attrs[i] = (byte) (k[i] ^ 0xA5);
        }
        return attrs;
    }

    private static byte[] huella(byte[] k) {
        byte[] h = new byte[16];
        for (int i = 0; i < 16; i++) {
            h[i] = (byte) (k[i] ^ k[i + 16] ^ k[i + 32] ^ (i * 13));
        }
        return h;
    }
}
