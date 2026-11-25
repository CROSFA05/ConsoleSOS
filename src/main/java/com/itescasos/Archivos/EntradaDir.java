package com.itescasos.Archivos;

/** Una entrada de directorio ya leída: nombre local -> número de inodo, y dónde está guardada. */
final class EntradaDir {
    final String nombre;
    final int inodo;
    final int bloqueLogico;
    final int ranura;

    EntradaDir(String nombre, int inodo, int bloqueLogico, int ranura) {
        this.nombre = nombre;
        this.inodo = inodo;
        this.bloqueLogico = bloqueLogico;
        this.ranura = ranura;
    }
}
