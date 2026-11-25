package com.itescasos.IO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Esqueleto común de los drivers: una cola, una solicitud en servicio y el
 * ciclo iniciar -> finalizar. Las subclases deciden qué solicitud sigue y cómo transfieren.
 */
public abstract class DriverBase implements Driver {
    private final String nombre;
    protected final List<SolicitudES> cola = new ArrayList<SolicitudES>();
    protected SolicitudES actual;
    protected int fin = -1;
    protected String detalle = "";

    protected DriverBase(String nombre) {
        this.nombre = nombre;
    }

    @Override
    public String nombre() { return nombre; }

    /** Quita de la cola la solicitud elegida, la deja en 'actual' y devuelve su duración (>= 1). */
    protected abstract int seleccionarYPreparar();

    /** Mueve los datos entre el buffer de la solicitud y el medio. */
    protected abstract void transferir(SolicitudES s) throws ErrorES;

    @Override
    public void encolar(SolicitudES s) { cola.add(s); }

    @Override
    public int pendientes() { return cola.size(); }

    @Override
    public List<SolicitudES> colaPendiente() { return Collections.unmodifiableList(new ArrayList<SolicitudES>(cola)); }

    @Override
    public boolean ocupado() { return actual != null; }

    @Override
    public SolicitudES enServicio() { return actual; }

    @Override
    public int finServicio() { return fin; }

    @Override
    public String detalleServicio() { return detalle; }

    @Override
    public SolicitudES iniciarSiguiente(int ahora) {
        if (actual != null || cola.isEmpty()) {
            return null;
        }
        int duracion = seleccionarYPreparar();
        if (duracion < 1) {
            throw new IllegalStateException("duración de servicio inválida: " + duracion);
        }
        actual.iniciar(ahora);
        actual.ponerDetalle(detalle);
        fin = ahora + duracion;
        return actual;
    }

    @Override
    public SolicitudES finalizarServicio(int ahora) {
        if (actual == null) {
            throw new IllegalStateException("el dispositivo " + nombre + " no tiene servicio en curso");
        }
        SolicitudES s = actual;
        try {
            transferir(s);
            s.terminar(EstadoSolicitud.COMPLETADA, "OK", ahora);
        } catch (ErrorES e) {
            s.terminar(EstadoSolicitud.FALLIDA, e.codigo(), ahora);
            s.ponerDetalle(e.getMessage());
        } catch (RuntimeException e) {
            s.terminar(EstadoSolicitud.FALLIDA, "ERROR_DE_DISPOSITIVO", ahora);
            s.ponerDetalle(String.valueOf(e.getMessage()));
        }
        actual = null;
        fin = -1;
        return s;
    }

    @Override
    public SolicitudES cancelar(int idSolicitud, int ahora) {
        for (int i = 0; i < cola.size(); i++) {
            if (cola.get(i).id() == idSolicitud) {
                SolicitudES s = cola.remove(i);
                s.terminar(EstadoSolicitud.CANCELADA, "CANCELADA", ahora);
                return s;
            }
        }
        return null;
    }
}
