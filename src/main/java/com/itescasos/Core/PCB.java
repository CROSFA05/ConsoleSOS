package com.itescasos.Core;

// Este PCB incluye el control de ráfagas de CPU, el consumo de E/S y contadores detallados para las métricas.
public class PCB {
    
    public final String pid;
    public final int tiempoLlegada;
    public final int rafagaCpuTotal;
    public final int rafagaIoTotal;
    public final int prioridad;
    public final int memoriaRequerida;

    public EstadoProceso estado;
    public int cpuRestante;
    public int ioRestante;
    public int tiempoPrimerDespacho = -1;
    public int tiempoFinalizacion = -1;
    public int tiempoEsperaListo = 0;
    public int tiempoBloqueado = 0;

    public boolean ioSolicitada = false;

    public PCB(String pid, int llegada, int cpu, int io, int prioridad, int memoria) {
        this.pid = pid;
        this.tiempoLlegada = llegada;
        this.rafagaCpuTotal = cpu;
        this.rafagaIoTotal = io;
        this.prioridad = prioridad;
        this.memoriaRequerida = memoria;
        this.estado = EstadoProceso.NUEVO;
        this.cpuRestante = cpu;
        this.ioRestante = io;
    }

    public int getTiempoRetorno() {
        return tiempoFinalizacion - tiempoLlegada;
    }

    public int getTiempoRespuesta() {
        return tiempoPrimerDespacho - tiempoLlegada;
    }
}