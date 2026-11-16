package com.itescasos.Memoria;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

//Contiene el motor de paginación, la tabla de marcos físicos y los 
// 4 algoritmos de reemplazo (FIFO, LRU, Óptimo y Reloj).

public class GestorMemoria {
    
    private final int totalMarcos;
    private final int tamañoMarcoBytes;

    public GestorMemoria(int totalMarcos, int tamañoMarcoBytes) {
        this.totalMarcos = totalMarcos;
        this.tamañoMarcoBytes = tamañoMarcoBytes;
    }

    //Simula una cadena de referencias de paginas y muestra la traza paso a paso
    public void simularPaginacion(List<Integer> referencias, String algoritmo) {
        int[] marcos = new int[totalMarcos];
        Arrays.fill(marcos, -1);      // -1 indica marco libre

        //Estructuras auxiliares para algoritmos

        Queue<Integer> colaFIFO = new LinkedList<>();
        Map<Integer, Integer> ultimoUsoLRU = new HashMap<>();
        boolean[] bitReferenciaReloj = new boolean[totalMarcos];
        int punteroReloj = 0;

        int fallosPagina = 0;
        int aciertos = 0;

        System.out.println("\n============================================================");
        System.out.println(" ADMINISTRADOR DE MEMORIA - ALGORITMO: " + algoritmo.toUpperCase() + " (Marcos: "+ totalMarcos + ")");
        System.out.println("============================================================");
        System.out.printf("%-6s %-8s %-25s %-12s%n", "Paso", "Página", "Estado de Marcos", "Resultado");
        System.out.println("------------------------------------------------------------");

        for(int i = 0; i < referencias.size(); i++) {
            int paginaActual = referencias.get(i);
            int indiceMarco = buscarEnMarcos(marcos, paginaActual);
            boolean esFallo = false;

            if (indiceMarco != -1) {
                //Acierto de pagina
                aciertos++;
                ultimoUsoLRU.put(paginaActual, i);
                bitReferenciaReloj[indiceMarco] = true;
            } else {
                //Fallo de pagina
                fallosPagina++;
                esFallo = true;
                int marcoLibre = buscarEnMarcos(marcos, -1);

                if (marcoLibre != -1) {
                    //Hay espacio disponible
                    marcos[marcoLibre] = paginaActual;
                    colaFIFO.add(marcoLibre);
                    ultimoUsoLRU.put(paginaActual, i);
                    bitReferenciaReloj[marcoLibre] = true;
                } else {
                    //Memoria llena: Aplicar politica de reemplazo
                    int marcoVictima = -1;

                    switch (algoritmo.toUpperCase()) {
                        case "FIFO" -> {marcoVictima = colaFIFO.poll();
                                        colaFIFO.add(marcoVictima);
                        }
                        case "LRU" -> marcoVictima = seleccionarVictimaLRU(marcos, ultimoUsoLRU);
                        case "OPTIMO" -> marcoVictima = seleccionarVictimaOptimo(marcos, referencias, i);
                        case "RELOJ" -> {while(true) {
                                            if (!bitReferenciaReloj[punteroReloj]) {
                                                marcoVictima = punteroReloj;
                                                punteroReloj = (punteroReloj + 1) % totalMarcos;
                                                break;
                                            }
                                            bitReferenciaReloj[punteroReloj] = false;
                                            punteroReloj = (punteroReloj + 1) % totalMarcos;
                                        }
                                        bitReferenciaReloj[marcoVictima] = true;
                        }
                        default -> throw new IllegalArgumentException("Algoritmo no soportado: " + algoritmo);
                    }

                    //Reemplazar pagina en el marco victima
                    int paginaSaliente = marcos[marcoVictima];
                    ultimoUsoLRU.remove(paginaSaliente);
                    marcos[marcoVictima] = paginaActual;
                    ultimoUsoLRU.put(paginaActual, i);
                }
            }

            System.out.printf("%-6d %-8d %-25s %-12s%n",
                    (i + 1), paginaActual, formatearMarcos(marcos), esFallo ? "[FALLO]" : "Acierto");
        }

        double tasaFallos = ((double) fallosPagina / referencias.size()) * 100.0;
        double tasaAciertos = ((double) aciertos / referencias.size()) * 100.0;

        System.out.println("------------------------------------------------------------");
        System.out.printf(" Total Referencias: %d | Fallos: %d | Aciertos: %d%n", referencias.size(), fallosPagina, aciertos);
        System.out.printf(" Tasa de Fallos:    %.2f%% | Hit Ratio: %.2f%%%n", tasaFallos, tasaAciertos);
        
        // Extra: Detección de Thrashing (Hiperpaginación) si los fallos superan el 60%
        if (tasaFallos > 60.0) {
            System.out.println(" [ALERTA DE SISTEMA]: Posible THRASHING detectado (tasa de fallos crítica).");
        }
    }

    //Traduce una direccion logica (virtual) a doreccion fisica y calcula fragmenacion interna
    public  void traducirDireccionYFragmentacion(int memoriaProcesoBytes, int direccionLogica, int[] tablaPaginasMarcos) {
        int paginasNecesarias = (int) Math.ceil((double) memoriaProcesoBytes / tamañoMarcoBytes);
        int memoriaAsignadaBytes = paginasNecesarias * tamañoMarcoBytes;
        int fragmentacionInterna = memoriaAsignadaBytes - memoriaProcesoBytes;

        int numeroPagina = direccionLogica / tamañoMarcoBytes;
        int desplazamiento = direccionLogica % tamañoMarcoBytes;

        System.out.println("\n--- TRADUCCIÓN MMU Y FRAGMENTACIÓN ---");
        System.out.println(" Tamaño de Marco/Página:  " + tamañoMarcoBytes + " bytes");
        System.out.println(" Memoria solicitada:      " + memoriaProcesoBytes + " bytes (" + paginasNecesarias + " páginas)");
        System.out.println(" Fragmentación interna:   " + fragmentacionInterna + " bytes");
        System.out.println(" Dirección Lógica:        " + direccionLogica + " -> [Pág: " + numeroPagina + " | Offset: " + desplazamiento + "]");

        if (numeroPagina >= 0 && numeroPagina < tablaPaginasMarcos.length && tablaPaginasMarcos[numeroPagina] != -1) {
            int marcoFisico = tablaPaginasMarcos[numeroPagina];
            int direccionFisica = (marcoFisico * tamañoMarcoBytes) + desplazamiento;
            System.out.println(" Dirección Física Real: " + direccionFisica + " (Marco #" + marcoFisico + ")");
        } else {
            System.out.println(" [ERROR]: Fallo de protección / Página no mapeada en RAM.");
        }
    }

    private int buscarEnMarcos(int[] marcos, int valor) {
        for(int i = 0; i < marcos.length; i++) {
            if (marcos[i] == valor) return i;
        }
        return  -1;
    }

    private int seleccionarVictimaLRU(int[] marcos, Map<Integer, Integer> ultimoUso) {
        int marcoVictima = 0;
        int menorTiempo = Integer.MAX_VALUE;
        for(int i = 0; i < marcos.length; i++) {
            int tiempo = ultimoUso.getOrDefault(marcos[i], -1);
            if (tiempo < menorTiempo) {
                menorTiempo = tiempo;
                marcoVictima = i;
            }
        }
        return marcoVictima;
    }

    private int seleccionarVictimaOptimo(int[] marcos, List<Integer> referencias, int indiceActual) {
        int marcoVictima = 0;
        int distanciaMasLejana = -1;

        for(int i = 0; i < marcos.length; i++) {
            int paginaEnMarco = marcos[i];
            int siguienteUso = Integer.MAX_VALUE;

            for(int j = indiceActual + 1; j < referencias.size(); j++) {
                if (referencias.get(j) == paginaEnMarco) {
                    siguienteUso = j;
                    break;
                }
            }

            if (siguienteUso > distanciaMasLejana) {
                distanciaMasLejana = siguienteUso;
                marcoVictima = i;
            }
        }
        return  marcoVictima;
    }

    private String formatearMarcos(int[] marcos) {
        StringBuilder sb = new StringBuilder("[ ");
        for(int m : marcos) {
            sb.append(m == -1 ? "-" : m).append(" ");
        }
        sb.append("]");
        return sb.toString();
    }
}
