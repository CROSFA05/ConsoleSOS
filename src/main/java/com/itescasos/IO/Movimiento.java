package com.itescasos.IO;

/** Un desplazamiento del cabezal: posición previa, nueva y distancia en cilindros. */
public final class Movimiento {
    public enum Tipo { NORMAL, EXTREMO, RETORNO }

    public final int anterior;
    public final int nueva;
    public final int distancia;
    public final Tipo tipo;
    private int idSolicitud;

    Movimiento(int anterior, int nueva, Tipo tipo, int idSolicitud) {
        this.anterior = anterior;
        this.nueva = nueva;
        this.distancia = Math.abs(nueva - anterior);
        this.tipo = tipo;
        this.idSolicitud = idSolicitud;
    }

    /** Solicitud cuyo servicio provocó el movimiento (los viajes a extremos se atribuyen a la que se atiende después). */
    public int idSolicitud() { return idSolicitud; }

    void atribuirA(int id) { this.idSolicitud = id; }

    @Override
    public String toString() {
        return anterior + "->" + nueva + "(" + distancia + (tipo == Tipo.NORMAL ? "" : "," + tipo.name().toLowerCase()) + ")";
    }
}
