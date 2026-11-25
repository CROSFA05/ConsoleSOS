package com.itescasos.Archivos;

import java.util.Collections;
import java.util.List;

/** Vista inmutable de un objeto del volumen (lo que devuelven stat y ls). */
public final class InfoArchivo {
    public final String nombre;
    public final String ruta;
    public final TipoObjeto tipo;
    /** Número de inodo (ubicación física en la tabla). */
    public final int inodo;
    /** Identificador estable del objeto: no cambia al renombrar y no se reutiliza. Es la clave para seguridad. */
    public final int idObjeto;
    /** Tamaño lógico en bytes (para directorios: bytes de bloques reservados). */
    public final int tamano;
    public final int flags;
    public final List<Integer> bloques;
    private final byte[] atributos;

    InfoArchivo(String nombre, String ruta, TipoObjeto tipo, int inodo, int idObjeto, int tamano,
                int flags, List<Integer> bloques, byte[] atributos) {
        this.nombre = nombre;
        this.ruta = ruta;
        this.tipo = tipo;
        this.inodo = inodo;
        this.idObjeto = idObjeto;
        this.tamano = tamano;
        this.flags = flags;
        this.bloques = Collections.unmodifiableList(bloques);
        this.atributos = atributos.clone();
    }

    /** Copia de los 48 bytes de atributos reservados (los usa el módulo de seguridad para cifrado). */
    public byte[] atributos() { return atributos.clone(); }

    public boolean cifrado() { return (flags & SistemaArchivos.FLAG_CIFRADO) != 0; }
}
