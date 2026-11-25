package com.itescasos.Archivos;

import java.io.IOException;
import java.nio.file.Path;

/** Punto de entrada público para conocer el tamaño de bloque de una imagen antes de abrirla. */
public final class ImagenDiscoAcceso {
    private ImagenDiscoAcceso() { }

    public static int tamBloqueDe(Path imagen) throws IOException {
        return Superbloque.tamBloqueDe(imagen);
    }
}
