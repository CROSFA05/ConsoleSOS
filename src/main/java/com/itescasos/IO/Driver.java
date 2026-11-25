package com.itescasos.IO;

import java.util.List;

/**
 * Driver de un dispositivo simulado. Separa el dispositivo (medio), el controlador
 * (cola y estado) y el software del driver (validar, iniciar, terminar, reportar errores).
 * El driver nunca consulta ni avanza el reloj: el administrador le dice la hora.
 */
public interface Driver {
    String nombre();

    /** "DISCO", "TERMINAL"... Una solicitud solo se aplica a un dispositivo de tipo compatible. */
    String tipo();

    /** Rechaza solicitudes inválidas para este tipo de dispositivo. */
    void validar(SolicitudES s) throws ErrorES;

    void encolar(SolicitudES s);

    int pendientes();

    List<SolicitudES> colaPendiente();

    boolean ocupado();

    SolicitudES enServicio();

    /** Instante de fin del servicio actual (o -1 si está libre). */
    int finServicio();

    /** Elige la siguiente solicitud según la política y la pone en servicio; null si no hay trabajo. */
    SolicitudES iniciarSiguiente(int ahora);

    /** Realiza la transferencia de la solicitud en servicio y la deja en estado terminal. */
    SolicitudES finalizarServicio(int ahora);

    /** Cancela una solicitud PENDIENTE; null si no está pendiente en este dispositivo. */
    SolicitudES cancelar(int idSolicitud, int ahora);

    /** Texto breve sobre el último servicio iniciado (p. ej. movimientos del cabezal). */
    String detalleServicio();
}
