package com.itescasos;

import java.util.Arrays;
import java.util.List;

import com.itescasos.Core.PCB;
import com.itescasos.Core.PlanificadorCPU;
import com.itescasos.Memoria.GestorMemoria;

public class App {
    public static void main(String[] args) {
        System.out.println("=== PRUEBA DEL MODULO 1: PLANIFICADOR DE CPU ===");

        // Creamos la lista de procesos del caso de control de la guia
        // Parametros: (PID, Llegada, CPU, E/S, Prioridad, Memoria)
        List<PCB> procesos = Arrays.asList(
                new PCB("P1", 0, 5, 0, 3, 8192),
                new PCB("P2", 0, 3, 0, 1, 8192),
                new PCB("P3", 0, 1, 0, 4, 8192),
                new PCB("P4", 0, 2, 0, 2, 8192),
                new PCB("P5", 0, 4, 0, 2, 8192));

        PlanificadorCPU planificador = new PlanificadorCPU();

        // Probamos los 4 algoritmos obligatorios
        planificador.simular(procesos, "FCFS", 0);
        planificador.simular(procesos, "SJF", 0);
        planificador.simular(procesos, "RR", 2);
        planificador.simular(procesos, "PRIORIDADES", 0);

        System.out.println("\n=== PRUEBA DEL MODULO 2: ADMINISTRADOR DE MEMORIA ===");
        List<Integer> cadenaReferencias = Arrays.asList(7, 0, 1, 2, 0, 3, 0, 4, 2, 3, 0, 3, 2);

        // 3 marcos físicos de 4096 bytes (4 KB) cada uno
        GestorMemoria memoria = new GestorMemoria(3, 4096);

        memoria.simularPaginacion(cadenaReferencias, "FIFO");
        memoria.simularPaginacion(cadenaReferencias, "LRU");
        memoria.simularPaginacion(cadenaReferencias, "OPTIMO");
        memoria.simularPaginacion(cadenaReferencias, "RELOJ");

        // Prueba extra: Traducción MMU y fragmentación interna para un proceso de
        // 10,000 bytes
        int[] tablaEjemplo = { 2, 0, 1 }; // Pág 0 -> Marco 2, Pág 1 -> Marco 0, Pág 2 -> Marco 1
        memoria.traducirDireccionYFragmentacion(10000, 5000, tablaEjemplo);
    }
}
