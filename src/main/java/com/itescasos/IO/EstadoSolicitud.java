package com.itescasos.IO;

public enum EstadoSolicitud {
    PENDIENTE, EN_SERVICIO, COMPLETADA, FALLIDA, CANCELADA;

    /** Un estado terminal ocurre exactamente una vez por solicitud. */
    public boolean esTerminal() {
        return this == COMPLETADA || this == FALLIDA || this == CANCELADA;
    }
}
