package com.itescasos.IO;

/**
 * Solicitud de E/S. Conserva identificador, PID, dispositivo, operación, destino,
 * tamaño, buffer, marcas de tiempo, estado y resultado.
 *
 * destino: para el disco es el número de bloque (el cilindro se deriva con
 * bloquesPorCilindro); la terminal no usa destino y debe ser 0.
 *
 * El buffer pertenece al dispositivo mientras la solicitud no termine: datos()
 * solo se puede consultar en un estado terminal.
 */
public class SolicitudES {
    private final int id;
    private final String pid;
    private final String dispositivo;
    private final Operacion operacion;
    private final int destino;
    private final int tamano;
    private final ModoES modo;
    private final int tCreacion;

    private byte[] buffer;
    private int tInicio = -1;
    private int tFin = -1;
    private EstadoSolicitud estado = EstadoSolicitud.PENDIENTE;
    private String resultado = "";
    private String detalle = "";

    SolicitudES(int id, String pid, String dispositivo, Operacion operacion, int destino,
                int tamano, ModoES modo, byte[] buffer, int tCreacion) {
        this.id = id;
        this.pid = pid;
        this.dispositivo = dispositivo;
        this.operacion = operacion;
        this.destino = destino;
        this.tamano = tamano;
        this.modo = modo;
        this.buffer = buffer;
        this.tCreacion = tCreacion;
    }

    public int id() { return id; }
    public String pid() { return pid; }
    public String dispositivo() { return dispositivo; }
    public Operacion operacion() { return operacion; }
    public int destino() { return destino; }
    public int tamano() { return tamano; }
    public ModoES modo() { return modo; }
    public int tCreacion() { return tCreacion; }
    public int tInicio() { return tInicio; }
    public int tFin() { return tFin; }
    public EstadoSolicitud estado() { return estado; }
    public String resultado() { return resultado; }
    public String detalle() { return detalle; }

    /** Tiempo entre la creación y el inicio del servicio (o el fin, si nunca se atendió). */
    public int espera() {
        int hasta = tInicio >= 0 ? tInicio : (tFin >= 0 ? tFin : tCreacion);
        return hasta - tCreacion;
    }

    /** Duración del servicio (0 si nunca se atendió). */
    public int servicio() {
        return (tInicio >= 0 && tFin >= 0) ? tFin - tInicio : 0;
    }

    /** Copia de los datos transferidos; solo disponible cuando la solicitud terminó. */
    public byte[] datos() {
        if (!estado.esTerminal()) {
            throw new IllegalStateException("el buffer de la solicitud " + id + " sigue en uso (estado " + estado + ")");
        }
        return buffer == null ? null : buffer.clone();
    }

    // ---- uso interno del módulo (drivers y administrador) ----

    byte[] bufferInterno() { return buffer; }

    void ponerBufferResultado(byte[] datos) { this.buffer = datos; }

    void ponerDetalle(String d) { this.detalle = d == null ? "" : d; }

    void iniciar(int t) {
        if (estado != EstadoSolicitud.PENDIENTE) {
            throw new IllegalStateException("solicitud " + id + " no está pendiente: " + estado);
        }
        estado = EstadoSolicitud.EN_SERVICIO;
        tInicio = t;
    }

    void terminar(EstadoSolicitud nuevo, String res, int t) {
        if (!nuevo.esTerminal()) {
            throw new IllegalArgumentException("estado no terminal: " + nuevo);
        }
        if (estado.esTerminal()) {
            throw new IllegalStateException("la solicitud " + id + " ya terminó (" + estado + ")");
        }
        estado = nuevo;
        resultado = res;
        tFin = t;
    }
}
