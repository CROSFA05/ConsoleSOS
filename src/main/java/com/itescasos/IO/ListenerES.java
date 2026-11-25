package com.itescasos.IO;

/**
 * Notificación al núcleo cuando una solicitud llega a su estado terminal
 * (COMPLETADA, FALLIDA o CANCELADA). Se invoca exactamente una vez por solicitud.
 * El núcleo decide: si la solicitud era SINCRONA, pasa al PID de BLOQUEADO a LISTO
 * (no a EJECUCION); si era ASINCRONA, deja el resultado disponible.
 */
public interface ListenerES {
    void alTerminar(SolicitudES solicitud);
}
