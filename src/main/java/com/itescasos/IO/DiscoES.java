package com.itescasos.IO;

import com.itescasos.Core.Reloj;

/**
 * Adaptador que usa el sistema de archivos: cada lectura/escritura de bloque se convierte en
 * una SolicitudES al driver de disco y espera su fin (modo autónomo). Así los archivos nunca
 * se saltan el driver y cada acceso queda en la cola, el log y las métricas del disco.
 */
public class DiscoES implements DispositivoBloques {
    private final AdministradorES es;
    private final String dispositivo;
    private final DispositivoBloques geometria;
    private String pid = "FS";

    public DiscoES(AdministradorES es, String dispositivo, DispositivoBloques geometria) {
        this.es = es;
        this.dispositivo = dispositivo;
        this.geometria = geometria;
    }

    /** PID al que se atribuyen las solicitudes (por defecto "FS"). */
    public void ponerPid(String pid) { this.pid = pid; }

    public Reloj reloj() { return es.reloj(); }

    @Override
    public int tamanoBloque() { return geometria.tamanoBloque(); }

    @Override
    public int totalBloques() { return geometria.totalBloques(); }

    @Override
    public byte[] leerBloque(int n) {
        SolicitudES s = es.enviar(pid, dispositivo, Operacion.LEER, n, tamanoBloque(), null, ModoES.SINCRONA);
        es.esperar(s.id());
        comprobar(s);
        return s.datos();
    }

    @Override
    public void escribirBloque(int n, byte[] datos) {
        SolicitudES s = es.enviar(pid, dispositivo, Operacion.ESCRIBIR, n, datos.length, datos, ModoES.SINCRONA);
        es.esperar(s.id());
        comprobar(s);
    }

    @Override
    public void cerrar() { geometria.cerrar(); }

    private void comprobar(SolicitudES s) {
        if (s.estado() != EstadoSolicitud.COMPLETADA) {
            throw new IllegalStateException("E/S de disco fallida (solicitud " + s.id() + "): " + s.resultado()
                    + (s.detalle().isEmpty() ? "" : " - " + s.detalle()));
        }
    }
}
