package com.itescasos.Core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bloque de control de proceso compartido por todos los módulos.
 *
 * Convención de ráfagas documentada: {@code cpu} es una lista de ráfagas de CPU y
 * {@code io} la lista de esperas de E/S que ocurren DESPUÉS de cada ráfaga de CPU.
 * Por eso {@code io.size()} puede ser {@code cpu.size()-1} (E/S entre ráfagas) o
 * {@code cpu.size()} (además una E/S final antes de terminar). El constructor
 * clásico {@code (pid, llegada, cpu, io, ...)} equivale a una sola ráfaga de CPU
 * seguida, si {@code io > 0}, de una E/S final.
 */
public class PCB {
    public final String pid;
    public final int tiempoLlegada;
    public final int rafagaCpuTotal;
    public final int rafagaIoTotal;
    public final int prioridad;
    public final int memoriaRequerida;

    public final List<Integer> rafagasCpu;
    public final List<Integer> rafagasIo;

    public EstadoProceso estado;
    public int cpuRestante;
    public int ioRestante;
    public int tiempoPrimerDespacho = -1;
    public int tiempoFinalizacion = -1;
    public int tiempoEsperaListo = 0;
    public int tiempoBloqueado = 0;

    /** Índice de la ráfaga de CPU en curso y de la E/S que le sigue. */
    public int indiceCpu = 0;
    public int solicitudIoId = -1;
    public boolean esperandoIoFinal = false;

    /** Compatibilidad con el motor antiguo. */
    public boolean ioSolicitada = false;

    public PCB(String pid, int llegada, int cpu, int io, int prioridad, int memoria) {
        this(pid, llegada,
                cpu > 0 ? Collections.singletonList(cpu) : Collections.<Integer>emptyList(),
                io > 0 ? Collections.singletonList(io) : Collections.<Integer>emptyList(),
                prioridad, memoria, false);
    }

    /** Constructor con ráfagas múltiples. */
    public PCB(String pid, int llegada, List<Integer> cpu, List<Integer> io, int prioridad, int memoria) {
        this(pid, llegada, cpu, io, prioridad, memoria, true);
    }

    private PCB(String pid, int llegada, List<Integer> cpu, List<Integer> io,
                int prioridad, int memoria, boolean validarRafagas) {
        if (pid == null || pid.trim().isEmpty()) {
            throw new IllegalArgumentException("el PID no puede estar vacío");
        }
        if (llegada < 0) {
            throw new IllegalArgumentException("llegada negativa para " + pid + ": " + llegada);
        }
        if (memoria < 0) {
            throw new IllegalArgumentException("memoria negativa para " + pid + ": " + memoria);
        }
        if (cpu == null || cpu.isEmpty()) {
            throw new IllegalArgumentException("el proceso " + pid + " necesita al menos una ráfaga de CPU positiva");
        }
        this.rafagasCpu = Collections.unmodifiableList(new ArrayList<Integer>(cpu));
        this.rafagasIo = Collections.unmodifiableList(io == null ? new ArrayList<Integer>() : new ArrayList<Integer>(io));
        if (this.rafagasIo.size() > this.rafagasCpu.size()
                || (validarRafagas && this.rafagasIo.size() < this.rafagasCpu.size() - 1)) {
            throw new IllegalArgumentException("cantidad de ráfagas de E/S incoherente para " + pid);
        }
        for (int r : this.rafagasCpu) {
            if (r <= 0) {
                throw new IllegalArgumentException("ráfaga de CPU no positiva para " + pid + ": " + r);
            }
        }
        for (int r : this.rafagasIo) {
            if (r < 0) {
                throw new IllegalArgumentException("ráfaga de E/S negativa para " + pid + ": " + r);
            }
        }
        this.pid = pid.trim();
        this.tiempoLlegada = llegada;
        this.prioridad = prioridad;
        this.memoriaRequerida = memoria;
        int totalCpu = 0;
        for (int r : this.rafagasCpu) {
            totalCpu += r;
        }
        int totalIo = 0;
        for (int r : this.rafagasIo) {
            totalIo += r;
        }
        this.rafagaCpuTotal = totalCpu;
        this.rafagaIoTotal = totalIo;
        this.estado = EstadoProceso.NUEVO;
        this.cpuRestante = this.rafagasCpu.get(0);
        this.ioRestante = this.rafagasIo.isEmpty() ? 0 : this.rafagasIo.get(0);
    }

    public int getTiempoRetorno() {
        return tiempoFinalizacion < 0 ? -1 : tiempoFinalizacion - tiempoLlegada;
    }

    public int getTiempoRespuesta() {
        return tiempoPrimerDespacho < 0 ? -1 : tiempoPrimerDespacho - tiempoLlegada;
    }

    @Override
    public String toString() {
        return pid + "(estado=" + estado + ", cpu=" + cpuRestante + ", llegada=" + tiempoLlegada + ")";
    }
}
