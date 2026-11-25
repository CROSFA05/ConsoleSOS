package com.itescasos.Archivos;

/** Error de una operación del sistema de archivos; el código es estable para pruebas y logs. */
public class ErrorFS extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Codigo {
        IMAGEN_INVALIDA, RUTA_INVALIDA, NOMBRE_INVALIDO, NO_EXISTE, YA_EXISTE, NO_ES_DIRECTORIO,
        ES_DIRECTORIO, DIRECTORIO_NO_VACIO, DIRECTORIO_LLENO, SIN_ESPACIO, SIN_INODOS,
        ARCHIVO_DEMASIADO_GRANDE, PERMISO_DENEGADO, ARCHIVO_EN_USO, DESCRIPTOR_INVALIDO,
        MODO_INVALIDO, OPERACION_INVALIDA
    }

    private final Codigo codigo;

    public ErrorFS(Codigo codigo, String mensaje) {
        super(codigo + ": " + mensaje);
        this.codigo = codigo;
    }

    public Codigo codigo() { return codigo; }
}
