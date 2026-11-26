package com.itescasos.Core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

import com.itescasos.IO.AdministradorES;
import com.itescasos.IO.DriverCpu;
import com.itescasos.IO.EstadoSolicitud;
import com.itescasos.IO.ModoES;
import com.itescasos.IO.Operacion;
import com.itescasos.IO.SolicitudES;

/**
 * Motor de planificación de la CPU. Conserva UNA sola línea de tiempo (el reloj
 * compartido), delega las ráfagas de E/S al módulo de E/S y devuelve un
 * {@link Resultado} con el Gantt y las métricas derivadas de lo ejecutado.
 *
 * Decisiones documentadas: prioridad numérica menor = mayor prioridad, sin
 * expropiación; SJF compara la ráfaga de CPU actual de quien ya llegó; RR
 * reencola al final; el cambio de contexto cuesta 0 ticks; el tiempo se mide en
 * intervalos [t, t+1) y la finalización se registra en t+1.
 *
 * Los ganchos permiten que memoria y E/S participen del mismo motor sin que la
 * CLI modifique colas internas: en cada frontera {@link Gancho#alTick} puede
 * encolar solicitudes, antes de ejecutar {@link Gancho#alDespachar} puede pedir
 * una referencia y devolver la duración de una carga de página (bloquea solo al
 * solicitante), y {@link Gancho#alTerminar} libera sus recursos.
 */
public class PlanificadorCPU {

    public static final String CPU_DRIVER = "cpu-io";
    public static final String MEM_DRIVER = "memoria-carga";
    private static final String[] ALGORITMOS = { "FCFS", "SJF", "RR", "PRIORIDADES" };

    /** Puntos de extensión del motor para la integración con otros módulos. */
    public interface Gancho {
        /** Se llama en cada frontera, antes de incorporar llegadas y elegir proceso. */
        void alTick(int t);

        /**
         * Se llama al despachar un proceso.
         * @return duración en ticks de una carga que debe bloquearlo; 0 si continúa
         */
        int alDespachar(PCB proceso, int t);

        /** Se llama cuando el proceso termina, para liberar marcos/aperturas. */
        void alTerminar(PCB proceso, int t);
    }

    public static class Resultado {
        public final String algoritmo;
        public final int quantum;
        public final int tiempoTotal;
        public final int cpuUtil;
        public final double esperaMedia;
        public final double retornoMedia;
        public final double respuestaMedia;
        public final List<PCB> procesos;
        public final String gantt;
        public final List<String> eventos;

        Resultado(String algoritmo, int quantum, List<PCB> procesos, List<Intervalo> intervalos,
                  int tiempoTotal, int cpuUtil, List<String> eventos) {
            this.algoritmo = algoritmo;
            this.quantum = quantum;
            this.procesos = procesos;
            this.tiempoTotal = tiempoTotal;
            this.cpuUtil = cpuUtil;
            this.eventos = eventos;
            this.gantt = construirGantt(intervalos, tiempoTotal);
            int n = procesos.size();
            double espera = 0, retorno = 0, respuesta = 0;
            for (PCB p : procesos) {
                espera += p.tiempoEsperaListo;
                retorno += Math.max(0, p.getTiempoRetorno());
                respuesta += Math.max(0, p.getTiempoRespuesta());
            }
            this.esperaMedia = n == 0 ? 0 : espera / n;
            this.retornoMedia = n == 0 ? 0 : retorno / n;
            this.respuestaMedia = n == 0 ? 0 : respuesta / n;
        }

        public double utilizacion() {
            return tiempoTotal == 0 ? 0 : 100.0 * cpuUtil / tiempoTotal;
        }

        public String tabla() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-6s %-8s %-6s %-8s %-8s %-8s %-8s%n",
                    "PID", "Llegada", "CPU", "Espera", "Bloq.", "Retorno", "Respuesta"));
            sb.append("----------------------------------------------------------------------------\n");
            for (PCB p : procesos) {
                sb.append(String.format("%-6s %-8d %-6d %-8d %-8d %-8d %-8d%n",
                        p.pid, p.tiempoLlegada, p.rafagaCpuTotal, p.tiempoEsperaListo,
                        p.tiempoBloqueado, Math.max(0, p.getTiempoRetorno()),
                        Math.max(0, p.getTiempoRespuesta())));
            }
            return sb.toString();
        }

        public String resumen() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(" Espera media:    %.2f%n", esperaMedia));
            sb.append(String.format(" Retorno medio:   %.2f%n", retornoMedia));
            sb.append(String.format(" Respuesta media: %.2f%n", respuestaMedia));
            sb.append(String.format(" Utilización CPU: %.2f%%%n", utilizacion()));
            return sb.toString();
        }

        @Override
        public String toString() {
            return "=== " + algoritmo + (algoritmo.equals("RR") ? " (q=" + quantum + ")" : "")
                    + " ===\nGantt: " + gantt + "\n" + tabla() + resumen();
        }
    }

    private static final class Intervalo {
        final int inicio;
        int fin;
        final String pid;

        Intervalo(int inicio, String pid) {
            this.inicio = inicio;
            this.fin = inicio + 1;
            this.pid = pid;
        }
    }

    private enum TipoEspera { RAFAGA_IO, CARGA_PAGINA }

    /** Método histórico: ejecuta y muestra por System.out. La CLI usa {@link #ejecutar}. */
    public void simular(List<PCB> procesosEntrada, String algoritmo, int quantum) {
        Resultado r = ejecutar(procesosEntrada, algoritmo, quantum);
        System.out.println(r);
    }

    public Resultado ejecutar(List<PCB> entrada, String algoritmo, int quantum) {
        return ejecutar(entrada, algoritmo, quantum, new Reloj(), null, null, null);
    }

    /** Compatibilidad: un solo callback de terminación. */
    public Resultado ejecutar(List<PCB> entrada, String algoritmo, int quantum, Reloj reloj,
                              AdministradorES es, RegistroEventos registro, Consumer<PCB> alTerminar) {
        final Consumer<PCB> fin = alTerminar;
        return ejecutarConGancho(entrada, algoritmo, quantum, reloj, es, registro, new Gancho() {
            @Override public void alTick(int t) { }
            @Override public int alDespachar(PCB proceso, int t) { return 0; }
            @Override public void alTerminar(PCB proceso, int t) {
                if (fin != null) fin.accept(proceso);
            }
        });
    }

    /**
     * Ejecuta la carga con un reloj y un administrador de E/S compartidos,
     * conectando memoria y E/S mediante los ganchos del motor.
     */
    public Resultado ejecutarConGancho(List<PCB> entrada, String algoritmo, int quantum, Reloj reloj,
                              AdministradorES es, RegistroEventos registro, final Gancho gancho) {
        String alg = normalizar(algoritmo);
        if (quantum < 0) {
            throw new IllegalArgumentException("quantum no puede ser negativo");
        }
        if (esRR(alg) && quantum == 0) {
            throw new IllegalArgumentException("RR requiere quantum > 0");
        }
        if (reloj == null) {
            reloj = new Reloj();
        }
        if (es == null) {
            es = new AdministradorES(reloj);
        }
        if (es.driver(CPU_DRIVER) == null) {
            es.registrar(new DriverCpu(CPU_DRIVER));
        }
        if (gancho != null && es.driver(MEM_DRIVER) == null) {
            es.registrar(new DriverCpu(MEM_DRIVER));
        }

        final List<PCB> todos = clonar(entrada);
        final Map<Integer, PCB> porSolicitud = new HashMap<Integer, PCB>();
        final Map<Integer, TipoEspera> tipoPorSolicitud = new HashMap<Integer, TipoEspera>();
        final Map<Integer, Boolean> finPorSolicitud = new HashMap<Integer, Boolean>();
        final Queue<PCB> listos = new LinkedList<PCB>();
        final List<Intervalo> intervalos = new ArrayList<Intervalo>();
        final List<String> eventos = new ArrayList<String>();

        es.ponerListener(new com.itescasos.IO.ListenerES() {
            @Override
            public void alTerminar(SolicitudES s) {
                PCB p = porSolicitud.remove(s.id());
                if (p == null || p.estado != EstadoProceso.BLOQUEADO) {
                    return;
                }
                p.solicitudIoId = -1;
                TipoEspera tipo = tipoPorSolicitud.remove(s.id());
                boolean ultima = Boolean.TRUE.equals(finPorSolicitud.remove(s.id()));
                String msg = "fin de E/S pid=" + p.pid + " solicitud=" + s.id()
                        + " tipo=" + tipo + " resultado=" + s.resultado() + " t=" + s.tFin();
                eventos.add(msg);
                if (registro != null) {
                    registro.emitir("CPU", p.pid, "solicitud=" + s.id(), "IO_FIN",
                            ultima ? "TERMINADO" : "LISTO");
                }
                if (ultima) {
                    terminar(p, s.tFin(), gancho, registro);
                } else {
                    if (tipo == TipoEspera.RAFAGA_IO) {
                        p.indiceCpu++;
                        p.cpuRestante = p.rafagasCpu.get(p.indiceCpu);
                    }
                    p.estado = EstadoProceso.LISTO;
                    listos.add(p);
                }
            }
        });

        PCB enEjecucion = null;
        int quantumRestante = 0;

        while (contarTerminados(todos) < todos.size()) {
            int t = reloj.ahora();
            // No se usa un plazo absoluto: el reloj es compartido con el FS y con
            // otras E/S, y una operación de archivo dentro de un gancho puede
            // avanzarlo legítimamente. El bloqueo real se detecta más abajo, cuando
            // no hay eventos pendientes ni procesos listos que puedan progresar.
            es.avanzarA(t);
            if (gancho != null) {
                gancho.alTick(t);
            }
            es.iniciarServicios();

            for (PCB p : todos) {
                if (p.estado == EstadoProceso.NUEVO && p.tiempoLlegada <= t) {
                    p.estado = EstadoProceso.LISTO;
                    listos.add(p);
                    if (registro != null) {
                        registro.emitir("CPU", p.pid, "-", "LLEGADA", "LISTO");
                    }
                }
            }
            if (enEjecucion != null && esRR(alg) && quantumRestante == 0
                    && enEjecucion.estado == EstadoProceso.EJECUCION) {
                enEjecucion.estado = EstadoProceso.LISTO;
                listos.add(enEjecucion);
                if (registro != null) {
                    registro.emitir("CPU", enEjecucion.pid, "-", "QUANTUM_EXPIRADO", "LISTO");
                }
                enEjecucion = null;
            }
            for (PCB p : todos) {
                if (p.estado == EstadoProceso.BLOQUEADO) {
                    p.tiempoBloqueado++;
                }
            }

            if (enEjecucion == null) {
                enEjecucion = seleccionar(listos, alg);
                if (enEjecucion != null) {
                    enEjecucion.estado = EstadoProceso.EJECUCION;
                    if (enEjecucion.tiempoPrimerDespacho < 0) {
                        enEjecucion.tiempoPrimerDespacho = t;
                    }
                    quantumRestante = esRR(alg) ? quantum : Integer.MAX_VALUE / 2;
                    if (registro != null) {
                        registro.emitir("CPU", enEjecucion.pid, "-", "DESPACHO", "EJECUCION");
                    }
                    int carga = gancho == null ? 0 : gancho.alDespachar(enEjecucion, t);
                    if (carga > 0) {
                        PCB p = enEjecucion;
                        p.estado = EstadoProceso.BLOQUEADO;
                        bloquear(p, MEM_DRIVER, carga, TipoEspera.CARGA_PAGINA, false,
                                listos, es, porSolicitud, tipoPorSolicitud, finPorSolicitud, registro);
                        enEjecucion = null;
                        if (registro != null) {
                            registro.emitir("MEMORIA", p.pid, "carga", "BLOQUEADO", "FALLO");
                        }
                    }
                }
            }
            for (PCB p : listos) {
                if (p != enEjecucion) {
                    p.tiempoEsperaListo++;
                }
            }

            if (enEjecucion == null) {
                agregarIntervalo(intervalos, t, "OCIOSO");
                boolean hayEvento = es.proximoEvento() >= 0;
                boolean hayLlegada = false;
                for (PCB p : todos) {
                    hayLlegada |= p.estado == EstadoProceso.NUEVO;
                }
                if (!hayEvento && !hayLlegada && listos.isEmpty()) {
                    throw new IllegalStateException("no hay evento que pueda desbloquear los procesos pendientes");
                }
                reloj.avanzarA(t + 1);
                continue;
            }

            agregarIntervalo(intervalos, t, enEjecucion.pid);
            enEjecucion.cpuRestante--;
            quantumRestante--;
            // El intervalo [t, t+1) ya se consumió: cualquier E/S o finalización
            // ocurre en la siguiente frontera, no dentro del tick que se ejecutó.
            reloj.avanzarA(t + 1);

            if (enEjecucion.cpuRestante == 0) {
                PCB p = enEjecucion;
                int k = p.indiceCpu;
                int duracion = k < p.rafagasIo.size() ? p.rafagasIo.get(k) : 0;
                boolean ultimaRafaga = k == p.rafagasCpu.size() - 1;
                if (duracion > 0) {
                    bloquear(p, CPU_DRIVER, duracion, TipoEspera.RAFAGA_IO, ultimaRafaga,
                            listos, es, porSolicitud, tipoPorSolicitud, finPorSolicitud, registro);
                    if (registro != null) {
                        registro.emitir("CPU", p.pid, "-", "BLOQUEADO", "IO_INICIADA");
                    }
                    enEjecucion = null;
                } else if (ultimaRafaga) {
                    terminar(p, reloj.ahora(), gancho, registro);
                    enEjecucion = null;
                } else {
                    p.indiceCpu++;
                    p.cpuRestante = p.rafagasCpu.get(p.indiceCpu);
                }
            }
        }

        int tiempoTotal = reloj.ahora();
        int cpuUtil = 0;
        for (Intervalo i : intervalos) {
            if (!"OCIOSO".equals(i.pid)) {
                cpuUtil += i.fin - i.inicio;
            }
        }
        return new Resultado(alg, quantum, todos, intervalos, tiempoTotal, cpuUtil, eventos);
    }

    // ------------------------------------------------------------------ internos

    /** Encola una carga y deja al proceso BLOQUEADO; si el driver rechaza, reanuda enseguida. */
    private static void bloquear(PCB p, String dispositivo, int duracion, TipoEspera tipo,
                                 boolean ultima, Queue<PCB> listos, AdministradorES es,
                                 Map<Integer, PCB> porSolicitud,
                                 Map<Integer, TipoEspera> tipoPorSolicitud,
                                 Map<Integer, Boolean> finPorSolicitud,
                                 RegistroEventos registro) {
        SolicitudES s = es.enviar(p.pid, dispositivo, Operacion.LEER, 0, duracion,
                null, ModoES.SINCRONA);
        p.ioSolicitada = true;
        p.estado = EstadoProceso.BLOQUEADO;
        if (s.estado() == EstadoSolicitud.PENDIENTE || s.estado() == EstadoSolicitud.EN_SERVICIO) {
            porSolicitud.put(s.id(), p);
            tipoPorSolicitud.put(s.id(), tipo);
            finPorSolicitud.put(s.id(), ultima);
            p.solicitudIoId = s.id();
            p.esperandoIoFinal = ultima;
            es.iniciarServicios();
            return;
        }
        // El driver rechazó la solicitud: no dejamos al proceso esperando para siempre.
        p.solicitudIoId = -1;
        if (ultima) {
            terminar(p, es.reloj().ahora(), null, registro);
        } else {
            if (tipo == TipoEspera.RAFAGA_IO) {
                p.indiceCpu++;
                p.cpuRestante = p.rafagasCpu.get(p.indiceCpu);
            }
            p.estado = EstadoProceso.LISTO;
            listos.add(p);
        }
    }

    private static List<PCB> clonar(List<PCB> entrada) {
        if (entrada == null || entrada.isEmpty()) {
            throw new IllegalArgumentException("la lista de procesos está vacía");
        }
        List<PCB> out = new ArrayList<PCB>();
        Map<String, Boolean> pids = new HashMap<String, Boolean>();
        for (PCB p : entrada) {
            if (p == null) {
                throw new IllegalArgumentException("proceso nulo en la carga");
            }
            if (pids.put(p.pid, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("PID duplicado: " + p.pid);
            }
            out.add(new PCB(p.pid, p.tiempoLlegada, p.rafagasCpu, p.rafagasIo,
                    p.prioridad, p.memoriaRequerida));
        }
        return out;
    }

    private static PCB seleccionar(Queue<PCB> listos, String algoritmo) {
        PCB elegido = null;
        for (PCB p : listos) {
            if (elegido == null) {
                elegido = p;
            } else if (algoritmo.equals("SJF")) {
                if (p.cpuRestante < elegido.cpuRestante) {
                    elegido = p;
                }
            } else if (algoritmo.equals("PRIORIDADES")) {
                if (p.prioridad < elegido.prioridad) {
                    elegido = p;
                }
            }
        }
        if (elegido != null) {
            listos.remove(elegido);
        }
        return elegido;
    }

    private static void terminar(PCB p, int t, Gancho gancho, RegistroEventos registro) {
        p.estado = EstadoProceso.TERMINADO;
        p.tiempoFinalizacion = t;
        p.cpuRestante = 0;
        p.ioRestante = 0;
        if (registro != null) {
            registro.emitir("CPU", p.pid, "-", "FIN", "TERMINADO");
        }
        if (gancho != null) {
            gancho.alTerminar(p, t);
        }
    }

    private static int contarTerminados(List<PCB> procesos) {
        int n = 0;
        for (PCB p : procesos) {
            if (p.estado == EstadoProceso.TERMINADO) {
                n++;
            }
        }
        return n;
    }

    private static void agregarIntervalo(List<Intervalo> intervalos, int inicio, String pid) {
        if (!intervalos.isEmpty()) {
            Intervalo ultimo = intervalos.get(intervalos.size() - 1);
            if (ultimo.fin == inicio && ultimo.pid.equals(pid)) {
                ultimo.fin = inicio + 1;
                return;
            }
        }
        intervalos.add(new Intervalo(inicio, pid));
    }

    private static String construirGantt(List<Intervalo> intervalos, int tiempoTotal) {
        StringBuilder sb = new StringBuilder();
        for (Intervalo i : intervalos) {
            sb.append("| ").append(i.inicio).append(" [").append(i.pid).append("] ");
        }
        sb.append("| ").append(tiempoTotal);
        return sb.toString();
    }

    private static String normalizar(String algoritmo) {
        if (algoritmo == null) {
            throw new IllegalArgumentException("algoritmo requerido");
        }
        String a = algoritmo.trim().toUpperCase(Locale.ROOT);
        if (a.equals("ROUND ROBIN") || a.equals("ROUND-ROBIN")) {
            a = "RR";
        }
        for (String valido : ALGORITMOS) {
            if (valido.equals(a)) {
                return a;
            }
        }
        throw new IllegalArgumentException("algoritmo desconocido: " + algoritmo
                + " (fcfs, sjf, rr, prioridades)");
    }

    private static boolean esRR(String algoritmo) {
        return "RR".equals(algoritmo);
    }
}
