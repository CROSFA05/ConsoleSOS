package com.itescasos.IO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Driver de disco con planificación FCFS, SCAN y C-SCAN.
 *
 * Geometría didáctica: cilindro = bloque / bloquesPorCilindro; los cilindros van de 0 a C-1.
 *
 * Convenciones (declaradas, y usadas igual en el cálculo teórico):
 *  - SCAN y C-SCAN (no LOOK/C-LOOK): si no quedan pedidos en la dirección actual pero sí en
 *    la otra, el cabezal viaja primero hasta el extremo (se cuenta) y entonces
 *      SCAN   invierte la dirección;
 *      C-SCAN salta al extremo opuesto (el retorno completo se cuenta) y sigue en la misma dirección.
 *  - El recorrido se detiene al atender la última petición: no hay vuelta vacía posterior.
 *  - La decisión se toma al iniciar cada servicio con lo que hay en la cola en ese momento.
 *  - Tiempo de servicio = ceil(distancia / cilindrosPorTick) + 1 tick de transferencia.
 *    (distancia en cilindros y tiempo en ticks son magnitudes distintas; esta es la conversión.)
 */
public class DriverDisco extends DriverBase {
    public static final String TIPO = "DISCO";
    public static final int TICKS_TRANSFERENCIA = 1;

    private final DispositivoBloques medio;
    private final int bloquesPorCilindro;
    private final int cilindros;
    private final int cilindrosPorTick;

    private PoliticaDisco politica;
    private Direccion direccion;
    private int cabezal;

    private final Set<Integer> defectuosos = new HashSet<Integer>();
    private final List<Movimiento> movimientos = new ArrayList<Movimiento>();
    private long distanciaTotal = 0;

    public DriverDisco(String nombre, DispositivoBloques medio, int bloquesPorCilindro,
                       PoliticaDisco politica, int cabezalInicial, Direccion direccionInicial,
                       int cilindrosPorTick) {
        super(nombre);
        if (bloquesPorCilindro < 1) {
            throw new IllegalArgumentException("bloquesPorCilindro debe ser >= 1");
        }
        if (cilindrosPorTick < 1) {
            throw new IllegalArgumentException("cilindrosPorTick debe ser >= 1");
        }
        this.medio = medio;
        this.bloquesPorCilindro = bloquesPorCilindro;
        this.cilindros = (medio.totalBloques() + bloquesPorCilindro - 1) / bloquesPorCilindro;
        this.cilindrosPorTick = cilindrosPorTick;
        if (cabezalInicial < 0 || cabezalInicial >= cilindros) {
            throw new IllegalArgumentException("cabezal inicial fuera de rango [0," + (cilindros - 1) + "]");
        }
        this.politica = politica;
        this.direccion = direccionInicial;
        this.cabezal = cabezalInicial;
    }

    @Override
    public String tipo() { return TIPO; }

    public int cilindros() { return cilindros; }
    public int cabezal() { return cabezal; }
    public Direccion direccion() { return direccion; }
    public PoliticaDisco politica() { return politica; }
    public long distanciaTotal() { return distanciaTotal; }
    /** Cambia la política; para comparar políticas con la misma entrada conviene una pila nueva. */
    public void ponerPolitica(PoliticaDisco p) { this.politica = p; }

    public int cilindroDe(int bloque) { return bloque / bloquesPorCilindro; }

    public List<Movimiento> movimientos() {
        return Collections.unmodifiableList(new ArrayList<Movimiento>(movimientos));
    }

    /** Simula un bloque dañado: cualquier transferencia sobre él falla con ERROR_DE_DISPOSITIVO. */
    public void marcarBloqueDefectuoso(int bloque) { defectuosos.add(bloque); }

    @Override
    public void validar(SolicitudES s) throws ErrorES {
        if (s.tamano() < 1 || s.tamano() > medio.tamanoBloque()) {
            throw new ErrorES("TAMANO_INVALIDO",
                    "tamaño " + s.tamano() + " fuera de [1," + medio.tamanoBloque() + "]");
        }
        if (s.destino() < 0 || s.destino() >= medio.totalBloques()) {
            throw new ErrorES("CILINDRO_FUERA_DE_RANGO",
                    "bloque " + s.destino() + " fuera de [0," + (medio.totalBloques() - 1)
                            + "] (cilindros 0.." + (cilindros - 1) + ")");
        }
        if (s.operacion() == Operacion.ESCRIBIR) {
            byte[] b = s.bufferInterno();
            if (b == null || b.length != s.tamano()) {
                throw new ErrorES("BUFFER_INVALIDO", "escribir requiere un buffer de exactamente " + s.tamano() + " bytes");
            }
        }
    }

    // ------------------------------------------------------------ planificación

    @Override
    protected int seleccionarYPreparar() {
        List<Movimiento> piernas = new ArrayList<Movimiento>();
        SolicitudES elegido;

        if (politica == PoliticaDisco.FCFS) {
            elegido = cola.get(0);
        } else if (politica == PoliticaDisco.SCAN) {
            elegido = mejorEnDireccion(direccion);
            if (elegido == null) {
                mover(extremo(direccion), Movimiento.Tipo.EXTREMO, 0, piernas);
                direccion = direccion.invertida();
                elegido = mejorEnDireccion(direccion);
            }
        } else { // CSCAN
            elegido = mejorEnDireccion(direccion);
            if (elegido == null) {
                mover(extremo(direccion), Movimiento.Tipo.EXTREMO, 0, piernas);
                mover(extremo(direccion.invertida()), Movimiento.Tipo.RETORNO, 0, piernas);
                elegido = mejorEnDireccion(direccion);
            }
        }

        mover(cilindroDe(elegido.destino()), Movimiento.Tipo.NORMAL, elegido.id(), piernas);
        for (Movimiento m : piernas) {
            m.atribuirA(elegido.id());
        }
        cola.remove(elegido);
        actual = elegido;

        int total = 0;
        StringBuilder sb = new StringBuilder();
        for (Movimiento m : piernas) {
            total += m.distancia;
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(m);
        }
        detalle = "cil=" + cilindroDe(elegido.destino())
                + " mov=" + (piernas.isEmpty() ? "sin_movimiento" : sb.toString())
                + " dist=" + total;
        return (total + cilindrosPorTick - 1) / cilindrosPorTick + TICKS_TRANSFERENCIA;
    }

    /** Pedido más cercano hacia 'dir' (incluye el cilindro actual); empates: el que llegó antes. */
    private SolicitudES mejorEnDireccion(Direccion dir) {
        SolicitudES mejor = null;
        int mejorCil = 0;
        for (SolicitudES s : cola) {
            int c = cilindroDe(s.destino());
            boolean adelante = dir == Direccion.ASCENDENTE ? c >= cabezal : c <= cabezal;
            if (!adelante) {
                continue;
            }
            boolean mejora = mejor == null
                    || (dir == Direccion.ASCENDENTE ? c < mejorCil : c > mejorCil);
            if (mejora) {
                mejor = s;
                mejorCil = c;
            }
        }
        return mejor;
    }

    private int extremo(Direccion dir) {
        return dir == Direccion.ASCENDENTE ? cilindros - 1 : 0;
    }

    private void mover(int nuevo, Movimiento.Tipo tipo, int idSolicitud, List<Movimiento> piernas) {
        if (nuevo == cabezal) {
            return; // sin desplazamiento no se registra movimiento
        }
        Movimiento m = new Movimiento(cabezal, nuevo, tipo, idSolicitud);
        movimientos.add(m);
        piernas.add(m);
        distanciaTotal += m.distancia;
        cabezal = nuevo;
    }

    // ------------------------------------------------------------ transferencia

    @Override
    protected void transferir(SolicitudES s) throws ErrorES {
        if (defectuosos.contains(s.destino())) {
            throw new ErrorES("ERROR_DE_DISPOSITIVO", "bloque " + s.destino() + " defectuoso");
        }
        int b = medio.tamanoBloque();
        if (s.operacion() == Operacion.LEER) {
            byte[] bloque = medio.leerBloque(s.destino());
            s.ponerBufferResultado(Arrays.copyOf(bloque, s.tamano()));
        } else if (s.tamano() == b) {
            medio.escribirBloque(s.destino(), s.bufferInterno());
        } else {
            byte[] bloque = medio.leerBloque(s.destino());
            System.arraycopy(s.bufferInterno(), 0, bloque, 0, s.tamano());
            medio.escribirBloque(s.destino(), bloque);
        }
    }
}
