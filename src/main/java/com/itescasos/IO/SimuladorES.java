package com.itescasos.IO;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.itescasos.Core.Reloj;

/**
 * Ejecuta la sección [io] de un escenario sobre una pila nueva (reloj, colas y contadores en
 * cero) por cada política, de modo que la comparación use exactamente la misma entrada.
 */
public class SimuladorES {

    /** Resultado de una corrida con una política. */
    public static class Corrida {
        public final PoliticaDisco politica;
        public final List<String> log = new ArrayList<String>();
        public final List<Movimiento> movimientos;
        public final List<SolicitudES> solicitudes;
        public final long distancia;
        public final int tiempoFinal;
        public final String resumen;
        public final List<String> salidaTerminal;

        Corrida(PoliticaDisco politica, List<Movimiento> movimientos, List<SolicitudES> solicitudes,
                long distancia, int tiempoFinal, String resumen, List<String> salidaTerminal) {
            this.politica = politica;
            this.movimientos = movimientos;
            this.solicitudes = solicitudes;
            this.distancia = distancia;
            this.tiempoFinal = tiempoFinal;
            this.resumen = resumen;
            this.salidaTerminal = salidaTerminal;
        }
    }

    public static List<Corrida> ejecutar(EscenarioIO e) {
        List<Corrida> corridas = new ArrayList<Corrida>();
        for (PoliticaDisco p : e.politicas) {
            corridas.add(ejecutar(e, p));
        }
        return corridas;
    }

    public static Corrida ejecutar(EscenarioIO e, PoliticaDisco politica) {
        Reloj reloj = new Reloj();
        MemoriaBloques medio = new MemoriaBloques(e.bloqueBytes, e.cilindros * e.bloquesPorCilindro);
        DriverDisco disco = new DriverDisco(DiscoSimulado.DISCO, medio, e.bloquesPorCilindro, politica,
                e.cabezal, e.direccion, e.cilindrosPorTick);
        DriverTerminal terminal = new DriverTerminal(DiscoSimulado.TERMINAL, 16);
        for (String linea : e.entradaTerminal) {
            terminal.ponerEntrada(linea);
        }
        AdministradorES es = new AdministradorES(reloj);
        es.registrar(disco);
        es.registrar(terminal);
        final List<String> log = new ArrayList<String>();
        es.ponerLog(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String s) { log.add(s); }
        });

        List<EscenarioIO.Peticion> orden = new ArrayList<EscenarioIO.Peticion>(e.peticiones);
        Collections.sort(orden, new Comparator<EscenarioIO.Peticion>() { // estable: respeta el orden del archivo
            @Override
            public int compare(EscenarioIO.Peticion a, EscenarioIO.Peticion b) { return Integer.compare(a.t, b.t); }
        });

        int i = 0;
        while (i < orden.size() || es.proximoEvento() >= 0) {
            int tLlegada = i < orden.size() ? orden.get(i).t : Integer.MAX_VALUE;
            int tEvento = es.proximoEvento() >= 0 ? es.proximoEvento() : Integer.MAX_VALUE;
            int t = Math.min(tLlegada, tEvento);
            reloj.avanzarA(Math.max(t, reloj.ahora()));
            es.avanzarA(reloj.ahora());
            while (i < orden.size() && orden.get(i).t <= reloj.ahora()) {
                EscenarioIO.Peticion p = orden.get(i++);
                es.enviar(p.pid, p.dispositivo, p.op, p.destino, p.tamano, p.datos, p.modo);
            }
            es.iniciarServicios();
        }

        Corrida c = new Corrida(politica, disco.movimientos(), es.todas(), disco.distanciaTotal(),
                reloj.ahora(), es.resumen(), terminal.salida());
        c.log.addAll(log);
        return c;
    }

    /** Imprime trazas, movimientos y totales; con varias políticas añade la comparación. */
    public static void imprimir(EscenarioIO e, List<Corrida> corridas, PrintStream out) {
        out.println("=== SIMULACION DE E/S: disco " + e.cilindros + " cilindros x " + e.bloquesPorCilindro
                + " bloque(s), cabezal inicial " + e.cabezal + " " + e.direccion
                + ", latencia: ceil(dist/" + e.cilindrosPorTick + ")+" + DriverDisco.TICKS_TRANSFERENCIA + " ticks ===");
        for (Corrida c : corridas) {
            out.println();
            out.println("--- Politica " + c.politica + " ---");
            for (String l : c.log) {
                out.println(l);
            }
            out.println("Movimientos del cabezal (previa -> nueva, distancia):");
            if (c.movimientos.isEmpty()) {
                out.println("  (ninguno)");
            }
            for (Movimiento m : c.movimientos) {
                out.println("  solicitud " + m.idSolicitud() + ": " + m.anterior + " -> " + m.nueva
                        + "  d=" + m.distancia + (m.tipo == Movimiento.Tipo.NORMAL ? "" : "  [" + m.tipo + "]"));
            }
            out.println("Recorrido total: " + c.distancia + " cilindros | fin de la simulacion en t=" + c.tiempoFinal);
            out.print(c.resumen);
            if (!c.salidaTerminal.isEmpty()) {
                out.println("Salida de la terminal: " + c.salidaTerminal);
            }
        }
        if (corridas.size() > 1) {
            out.println();
            out.println("=== COMPARACION (misma geometria, cola y convenciones) ===");
            out.printf("%-8s %-22s %-8s%n", "Politica", "Recorrido (cilindros)", "t final");
            for (Corrida c : corridas) {
                out.printf("%-8s %-22d %-8d%n", c.politica, c.distancia, c.tiempoFinal);
            }
        }
    }
}
