package com.itescasos.IO;

/**
 * Dispositivo simulado usado por el núcleo para las ráfagas de E/S declaradas en
 * el PCB. El tamaño de la solicitud es la duración exacta en ticks, de modo que
 * la espera del proceso coincide con la ráfaga indicada sin depender de la
 * geometría del disco. Sus solicitudes pasan por la misma cola, reloj, log y
 * notificaciones que el resto de dispositivos.
 */
public class DriverCpu extends DriverBase {

    public static final String TIPO = "SIMULADO";

    public DriverCpu(String nombre) {
        super(nombre);
    }

    @Override
    public String tipo() {
        return TIPO;
    }

    @Override
    public void validar(SolicitudES s) throws ErrorES {
        if (s.operacion() != Operacion.LEER) {
            throw new ErrorES("OPERACION_INVALIDA", "el dispositivo simulado solo admite LEER");
        }
        if (s.tamano() < 1) {
            throw new ErrorES("TAMANO_INVALIDO", "la duración debe ser >= 1 tick");
        }
    }

    @Override
    protected int seleccionarYPreparar() {
        actual = cola.remove(0);
        detalle = "duracion=" + actual.tamano() + " ticks";
        return actual.tamano();
    }

    @Override
    protected void transferir(SolicitudES s) {
        // No hay datos que transferir: solo se_modela el paso del tiempo.
    }
}
