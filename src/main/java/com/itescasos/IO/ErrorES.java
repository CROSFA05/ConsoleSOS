package com.itescasos.IO;

/** Error de validación o de transferencia de un dispositivo; lleva un código estable para el log. */
public class ErrorES extends Exception {
    private static final long serialVersionUID = 1L;
    private final String codigo;

    public ErrorES(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
