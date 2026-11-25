package com.itescasos.IO;

/** Error de sintaxis o de validación en un archivo de escenario, con su línea. */
public class ErrorEscenario extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int linea;

    public ErrorEscenario(int linea, String mensaje) {
        super(linea > 0 ? "línea " + linea + ": " + mensaje : mensaje);
        this.linea = linea;
    }

    public int linea() { return linea; }
}
