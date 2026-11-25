package com.itescasos.IO;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Driver de terminal: dispositivo de caracteres, sin cilindros, cola FIFO.
 * ESCRIBIR agrega una línea a la salida; LEER consume una línea de la entrada simulada
 * (si no hay datos, la solicitud falla con SIN_DATOS: nunca espera para siempre).
 * Tiempo de servicio = 1 + (tamaño-1)/bytesPorTick.
 */
public class DriverTerminal extends DriverBase {
    public static final String TIPO = "TERMINAL";
    public static final int TAMANO_MAXIMO = 1024;

    private final int bytesPorTick;
    private final LinkedList<String> entrada = new LinkedList<String>();
    private final List<String> salida = new ArrayList<String>();

    public DriverTerminal(String nombre, int bytesPorTick) {
        super(nombre);
        if (bytesPorTick < 1) {
            throw new IllegalArgumentException("bytesPorTick debe ser >= 1");
        }
        this.bytesPorTick = bytesPorTick;
    }

    @Override
    public String tipo() { return TIPO; }

    /** Agrega una línea que un LEER posterior podrá consumir (teclado simulado). */
    public void ponerEntrada(String linea) { entrada.add(linea); }

    public List<String> salida() { return Collections.unmodifiableList(new ArrayList<String>(salida)); }

    @Override
    public void validar(SolicitudES s) throws ErrorES {
        if (s.destino() != 0) {
            throw new ErrorES("DESTINO_NO_APLICA", "la terminal no usa cilindros ni bloques; destino debe ser 0");
        }
        if (s.tamano() < 1 || s.tamano() > TAMANO_MAXIMO) {
            throw new ErrorES("TAMANO_INVALIDO", "tamaño " + s.tamano() + " fuera de [1," + TAMANO_MAXIMO + "]");
        }
        if (s.operacion() == Operacion.ESCRIBIR) {
            byte[] b = s.bufferInterno();
            if (b == null || b.length != s.tamano()) {
                throw new ErrorES("BUFFER_INVALIDO", "escribir requiere un buffer de exactamente " + s.tamano() + " bytes");
            }
        }
    }

    @Override
    protected int seleccionarYPreparar() {
        actual = cola.remove(0);
        detalle = "fifo";
        return 1 + (actual.tamano() - 1) / bytesPorTick;
    }

    @Override
    protected void transferir(SolicitudES s) throws ErrorES {
        if (s.operacion() == Operacion.ESCRIBIR) {
            salida.add(new String(s.bufferInterno(), StandardCharsets.UTF_8));
        } else {
            if (entrada.isEmpty()) {
                throw new ErrorES("SIN_DATOS", "no hay entrada disponible en " + nombre());
            }
            byte[] bytes = entrada.removeFirst().getBytes(StandardCharsets.UTF_8);
            s.ponerBufferResultado(Arrays.copyOf(bytes, Math.min(bytes.length, s.tamano())));
        }
    }
}
