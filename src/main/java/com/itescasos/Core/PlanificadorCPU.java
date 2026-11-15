package com.itescasos.Core;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

// Este motor simula el paso del tiempo por ticks, gestionando la cola de listos, la cola de bloqueados y el desempate.
public class PlanificadorCPU {
    
    public void simular(List<PCB> procesosEntrada, String algoritmo, int quantum) {
        // Clonamos la lista para mantener los datos limpios en cada prueba
        List<PCB> todos = new ArrayList<>();
        for(PCB p : procesosEntrada) {
            todos.add(new PCB(p.pid, p.tiempoLlegada, p.rafagaCpuTotal, p.rafagaIoTotal, p.prioridad, p.memoriaRequerida));
        }

        Queue<PCB> colaListos = new LinkedList<>();
        List<PCB> colaBloqueados = new ArrayList<>();
        List<PCB> terminados = new ArrayList<>();
        StringBuilder gantt = new StringBuilder();

        int reloj = 0;
        int cpuUtil = 0;
        PCB enEjecucion = null;
        int quantumConsumido = 0;
        String ultimoPidGantt = "";

        while(terminados.size() < todos.size()) {
            // 1. Llegadas de nuevos procesos al sistema
            for(PCB p : todos) {
                if (p.estado == EstadoProceso.NUEVO && p.tiempoLlegada == reloj) {
                    p.estado = EstadoProceso.LISTO;
                    colaListos.add(p);
                }
            }

            // 2. Gestionar procesos bloqueados (E/S)
            List<PCB> listosPorIo = new ArrayList<>();
            for(PCB p : colaBloqueados) {
                p.tiempoBloqueado++;
                p.ioRestante--;
                if (p.ioRestante <= 0) {
                    p.estado = EstadoProceso.LISTO;
                    listosPorIo.add(p);
                }
            }
            
            colaBloqueados.removeAll(listosPorIo);
            colaListos.addAll(listosPorIo); // Regresan a la cola de listos al terminar E/S

            // 3. Expropiación por fin de quantum (Round Robin)
            if (enEjecucion != null && algoritmo.equalsIgnoreCase("RR") && quantumConsumido == quantum) {
                enEjecucion.estado = EstadoProceso.LISTO;
                colaListos.add(enEjecucion);
                enEjecucion = null;
                quantumConsumido = 0;
            }

            // 4. Asignar CPU si está libre y hay procesos listos
            if (enEjecucion == null && !colaListos.isEmpty()) {
                enEjecucion = seleccionarSiguiente(colaListos, algoritmo);
                enEjecucion.estado = EstadoProceso.EJECUCION;
                quantumConsumido = 0;
                if (enEjecucion.tiempoPrimerDespacho == -1) {
                    enEjecucion.tiempoPrimerDespacho = reloj;
                }
            }

            // 5. Ejecutar un tick de CPU
            if (enEjecucion != null) {
                if (!enEjecucion.pid.equals(ultimoPidGantt)) {
                    gantt.append("| ").append(reloj).append(" [").append(enEjecucion.pid).append("] ");
                    ultimoPidGantt = enEjecucion.pid;
                }
                enEjecucion.cpuRestante--;
                quantumConsumido++;
                cpuUtil++;

                // Simular que el proceso solicita E/S a la mitad de su ejecución
                if (enEjecucion.rafagaIoTotal > 0 && !enEjecucion.ioSolicitada && enEjecucion.cpuRestante == enEjecucion.rafagaCpuTotal / 2) {
                    enEjecucion.ioSolicitada = true;
                    enEjecucion.estado = EstadoProceso.BLOQUEADO;
                    colaBloqueados.add(enEjecucion);
                    enEjecucion = null;
                    quantumConsumido = 0;
                } else if(enEjecucion.cpuRestante == 0) {
                    // El proceso ha finalizado
                    enEjecucion.estado = EstadoProceso.TERMINADO;
                    enEjecucion.tiempoFinalizacion = reloj + 1;
                    terminados.add(enEjecucion);
                    enEjecucion = null;
                    quantumConsumido = 0;
                }

                // Acumular tiempo de espera solo a los que están formados en listos
                for(PCB listo : colaListos) {
                    listo.tiempoEsperaListo++;
                }
            } else {
                if (!"IDLE".equals(ultimoPidGantt)) {
                    gantt.append("| ").append(reloj).append(" [OCIOSO] ");
                    ultimoPidGantt = "IDLE";
                }
            }

            reloj++;
        }
        gantt.append("| ").append(reloj);

        imprimirResultados(algoritmo, quantum, todos, gantt.toString(), cpuUtil, reloj);
    }

    private PCB seleccionarSiguiente(Queue<PCB> colaListos, String algoritmo) {
        List<PCB> lista = new ArrayList<>(colaListos);
        PCB elegido = lista.get(0);

        if (algoritmo.equalsIgnoreCase("SJF")) {
            for(PCB p : lista) {
                if (p.cpuRestante < elegido.cpuRestante) {
                    elegido = p;
                }
            }
        } else if(algoritmo.equalsIgnoreCase("PRIORIDADES")) {
            for(PCB p : lista) {
                if (p.prioridad < elegido.prioridad) {
                    elegido = p;
                }
            }
        }
        colaListos.remove(elegido);
        return elegido;
    }

    private void imprimirResultados(String algoritmo, int quantum, List<PCB> procesos, String gantt, int cpuUtil, int tiempoTotal) {
        System.out.println("\n============================================================");
        System.out.println(" ALGORITMO: " + algoritmo.toUpperCase() + (algoritmo.equalsIgnoreCase("RR") ? " (q =" + quantum + ")" : ""));
        System.out.println(" Gantt: " + gantt);
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-6s %-8s %-8s %-8s %-8s %-10s%n", "PID", "Llegada", "CPU", "Espera", "Bloq.", "Retorno");

        double sumaEspera = 0;
        double sumaRetorno = 0;

        for(PCB p : procesos) {
            sumaEspera += p.tiempoEsperaListo;
            sumaRetorno += p.getTiempoRetorno();
            System.out.printf("%-6s %-8d %-8d %-8d %-8d %-10d%n",
                    p.pid, p.tiempoLlegada, p.rafagaCpuTotal, p.tiempoEsperaListo, p.tiempoBloqueado, p.getTiempoRetorno());
        }

        double esperaMedia = sumaEspera / procesos.size();
        double retornoMedio = sumaRetorno / procesos.size();
        double utilizacion = ((double) cpuUtil / tiempoTotal) * 100.0;

        System.out.println("------------------------------------------------------------");
        System.out.printf(" Espera media:    %.2f%n", esperaMedia);
        System.out.printf(" Retorno medio:   %.2f%n", retornoMedio);
        System.out.printf(" Utilización CPU: %.2f%%%n", utilizacion);
    }
}