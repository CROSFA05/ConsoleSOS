package com.itescasos.IO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.itescasos.Core.Reloj;

/**
 * Subsistema de E/S: registra drivers, recibe solicitudes, las pone en cola, programa su
 * fin en el reloj común (no hay esperas reales) y avisa al núcleo mediante un ListenerES.
 *
 * Uso integrado (el núcleo es dueño del reloj). En cada frontera de tiempo t:
 *   1. reloj.avanzarA(t); es.avanzarA(t);      // resuelve finales de servicio <= t y notifica
 *   2. es.enviar(...) por cada llegada en t;    // solo encola
 *   3. es.iniciarServicios();                   // los dispositivos libres eligen según su política
 * Así todas las solicitudes que llegan en el mismo instante compiten en la misma elección.
 * Entre fronteras conviene avanzar a es.proximoEvento() para no dejar un dispositivo ocioso.
 *
 * Uso autónomo (CLI de archivos, pruebas): enviar(...) + esperar(id), que avanza el reloj
 * hasta que la solicitud termina.
 */
public class AdministradorES {
    private final Reloj reloj;
    private final Map<String, Driver> drivers = new LinkedHashMap<String, Driver>();
    private final Map<Integer, SolicitudES> solicitudes = new LinkedHashMap<Integer, SolicitudES>();
    private int siguienteId = 1;
    private ListenerES listener;
    private Consumer<String> log;
    private boolean procesando = false;

    public AdministradorES(Reloj reloj) {
        this.reloj = reloj;
    }

    public void registrar(Driver d) {
        if (drivers.containsKey(d.nombre())) {
            throw new IllegalArgumentException("dispositivo duplicado: " + d.nombre());
        }
        drivers.put(d.nombre(), d);
    }

    public Driver driver(String nombre) { return drivers.get(nombre); }

    public List<Driver> drivers() { return new ArrayList<Driver>(drivers.values()); }

    public void ponerListener(ListenerES l) { this.listener = l; }

    /** Destino de las líneas de log (null = sin log). */
    public void ponerLog(Consumer<String> log) { this.log = log; }

    public Reloj reloj() { return reloj; }

    // ------------------------------------------------------------ solicitudes

    /**
     * Crea una solicitud y la deja pendiente en la cola del dispositivo. Una solicitud inválida
     * (dispositivo desconocido, tipo incompatible, rango o tamaño inválidos) nace ya FALLIDA:
     * termina una sola vez y se notifica, así el solicitante nunca queda esperando.
     */
    public SolicitudES enviar(String pid, String dispositivo, Operacion op, int destino,
                              int tamano, byte[] datos, ModoES modo) {
        int t = reloj.ahora();
        SolicitudES s = new SolicitudES(siguienteId++, pid, dispositivo, op, destino, tamano, modo,
                datos == null ? null : datos.clone(), t);
        solicitudes.put(s.id(), s);

        Driver d = drivers.get(dispositivo);
        try {
            if (pid == null || pid.isEmpty()) {
                throw new ErrorES("PID_INVALIDO", "se requiere un PID");
            }
            if (op == null || modo == null) {
                throw new ErrorES("SOLICITUD_INVALIDA", "operación y modo son obligatorios");
            }
            if (d == null) {
                throw new ErrorES("DISPOSITIVO_DESCONOCIDO", "no existe el dispositivo '" + dispositivo + "'");
            }
            d.validar(s);
        } catch (ErrorES e) {
            s.terminar(EstadoSolicitud.FALLIDA, e.codigo(), t);
            s.ponerDetalle(e.getMessage());
            registrarTerminal(s);
            return s;
        }
        emitir(t, s, "SOLICITUD", "pendiente", "op=" + op + " destino=" + destino + " tam=" + tamano + " modo=" + modo);
        d.encolar(s);
        return s;
    }

    /** Cancela una solicitud pendiente (una en servicio ya no se puede cancelar). */
    public boolean cancelar(int idSolicitud) {
        SolicitudES s = solicitudes.get(idSolicitud);
        if (s == null) {
            return false;
        }
        Driver d = drivers.get(s.dispositivo());
        if (d == null) {
            return false;
        }
        SolicitudES c = d.cancelar(idSolicitud, reloj.ahora());
        if (c == null) {
            return false;
        }
        registrarTerminal(c);
        return true;
    }

    /** Cancela todo lo pendiente de un PID (p. ej. al terminar el proceso); devuelve cuántas. */
    public int cancelarDePid(String pid) {
        int n = 0;
        for (Driver d : drivers.values()) {
            for (SolicitudES s : d.colaPendiente()) {
                if (s.pid().equals(pid) && cancelar(s.id())) {
                    n++;
                }
            }
        }
        return n;
    }

    public SolicitudES consultar(int idSolicitud) { return solicitudes.get(idSolicitud); }

    public List<SolicitudES> todas() { return new ArrayList<SolicitudES>(solicitudes.values()); }

    // ------------------------------------------------------------ avance del tiempo

    /** Instante del siguiente fin de servicio, o -1 si ningún dispositivo está ocupado. */
    public int proximoEvento() {
        int mejor = -1;
        for (Driver d : drivers.values()) {
            if (d.ocupado() && (mejor < 0 || d.finServicio() < mejor)) {
                mejor = d.finServicio();
            }
        }
        return mejor;
    }

    /**
     * Resuelve todos los servicios que terminan en o antes de t (en orden de tiempo; empate:
     * orden de registro del dispositivo). Cada fin notifica al núcleo. Si un servicio termina
     * antes de t, el dispositivo atiende enseguida la siguiente solicitud en ese instante.
     */
    public void avanzarA(int t) {
        if (procesando) {
            return;
        }
        procesando = true;
        try {
            while (true) {
                Driver sig = null;
                for (Driver d : drivers.values()) {
                    if (d.ocupado() && d.finServicio() <= t && (sig == null || d.finServicio() < sig.finServicio())) {
                        sig = d;
                    }
                }
                if (sig == null) {
                    return;
                }
                int tf = sig.finServicio();
                SolicitudES s = sig.finalizarServicio(tf);
                registrarTerminal(s);
                if (tf < t) {
                    iniciarEn(sig, tf);
                }
            }
        } finally {
            procesando = false;
        }
    }

    /** Cada dispositivo libre con trabajo pendiente inicia su siguiente servicio en el instante actual. */
    public void iniciarServicios() {
        for (Driver d : drivers.values()) {
            iniciarEn(d, reloj.ahora());
        }
    }

    /**
     * Modo autónomo: avanza el reloj hasta que la solicitud termine y la devuelve.
     * No usar cuando el núcleo es quien avanza el reloj.
     */
    public SolicitudES esperar(int idSolicitud) {
        SolicitudES s = solicitudes.get(idSolicitud);
        if (s == null) {
            throw new IllegalArgumentException("solicitud desconocida: " + idSolicitud);
        }
        iniciarServicios();
        while (!s.estado().esTerminal()) {
            int prox = proximoEvento();
            if (prox < 0) {
                throw new IllegalStateException("la solicitud " + idSolicitud + " no tiene evento que pueda completarla");
            }
            reloj.avanzarA(Math.max(prox, reloj.ahora()));
            avanzarA(reloj.ahora());
            iniciarServicios();
        }
        return s;
    }

    // ------------------------------------------------------------ internos

    private void iniciarEn(Driver d, int t) {
        SolicitudES s = d.iniciarSiguiente(t);
        if (s != null) {
            emitir(t, s, "IO_INICIO", "en_servicio",
                    "espera=" + s.espera() + " fin_previsto=" + d.finServicio() + " " + d.detalleServicio());
        }
    }

    private void registrarTerminal(SolicitudES s) {
        String evento = s.estado() == EstadoSolicitud.CANCELADA ? "IO_CANCELADA" : "IO_FIN";
        emitir(s.tFin(), s, evento, s.resultado(),
                "espera=" + s.espera() + " servicio=" + s.servicio()
                        + (s.estado() == EstadoSolicitud.FALLIDA && !s.detalle().isEmpty() ? " causa=\"" + s.detalle() + "\"" : ""));
        if (listener != null) {
            listener.alTerminar(s);
        }
    }

    private void emitir(int t, SolicitudES s, String evento, String resultado, String extra) {
        if (log == null) {
            return;
        }
        log.accept("t=" + t + " modulo=IO pid=" + s.pid() + " solicitud=" + s.id()
                + " dispositivo=" + s.dispositivo() + " evento=" + evento
                + " resultado=" + resultado + " " + extra);
    }

    /** Resumen por dispositivo: solicitudes por estado final, esperas y movimiento del cabezal. */
    public String resumen() {
        StringBuilder sb = new StringBuilder();
        for (Driver d : drivers.values()) {
            int ok = 0, mal = 0, canc = 0, pend = 0, n = 0;
            long espera = 0, servicio = 0;
            for (SolicitudES s : solicitudes.values()) {
                if (!s.dispositivo().equals(d.nombre())) {
                    continue;
                }
                switch (s.estado()) {
                    case COMPLETADA: ok++; break;
                    case FALLIDA: mal++; break;
                    case CANCELADA: canc++; break;
                    default: pend++; break;
                }
                if (s.estado() == EstadoSolicitud.COMPLETADA) {
                    n++;
                    espera += s.espera();
                    servicio += s.servicio();
                }
            }
            sb.append(String.format("%-10s %-8s completadas=%d falladas=%d canceladas=%d sin_terminar=%d",
                    d.nombre(), d.tipo(), ok, mal, canc, pend));
            if (n > 0) {
                sb.append(String.format(" espera_media=%.2f servicio_medio=%.2f", espera / (double) n, servicio / (double) n));
            }
            if (d instanceof DriverDisco) {
                DriverDisco dd = (DriverDisco) d;
                sb.append(" politica=").append(dd.politica()).append(" recorrido_cabezal=").append(dd.distanciaTotal()).append(" cilindros");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
