package com.itescasos.Archivos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resultado de fsck: separa lo reparado, lo detectado y lo que queda pendiente (no reparable con certeza). */
public final class ReporteFsck {
    public enum Estado { INFORMATIVO, DETECTADO, REPARADO, PENDIENTE }

    public static final class Hallazgo {
        public final String codigo;
        public final String descripcion;
        public final Estado estado;

        Hallazgo(String codigo, String descripcion, Estado estado) {
            this.codigo = codigo;
            this.descripcion = descripcion;
            this.estado = estado;
        }

        @Override
        public String toString() { return "[" + estado + "] " + codigo + ": " + descripcion; }
    }

    private final List<Hallazgo> hallazgos = new ArrayList<Hallazgo>();
    private boolean fatal = false;

    void agregar(String codigo, String desc, Estado e) { hallazgos.add(new Hallazgo(codigo, desc, e)); }

    void marcarFatal() { fatal = true; }

    public List<Hallazgo> hallazgos() { return Collections.unmodifiableList(hallazgos); }

    /** true si no se pudo ni empezar (superbloque o raíz inservibles): solo un respaldo recupera el volumen. */
    public boolean fatal() { return fatal; }

    public int contar(Estado e) {
        int n = 0;
        for (Hallazgo h : hallazgos) {
            if (h.estado == e) {
                n++;
            }
        }
        return n;
    }

    /** Sin daños detectados ni pendientes (los informativos y los ya reparados no cuentan). */
    public boolean consistente() {
        return !fatal && contar(Estado.DETECTADO) == 0 && contar(Estado.PENDIENTE) == 0;
    }

    public String texto() {
        StringBuilder sb = new StringBuilder();
        for (Hallazgo h : hallazgos) {
            sb.append(h).append('\n');
        }
        sb.append(String.format("Resumen: %d detectados sin reparar, %d reparados, %d pendientes (no reparables con certeza), %d informativos%s%n",
                contar(Estado.DETECTADO), contar(Estado.REPARADO), contar(Estado.PENDIENTE), contar(Estado.INFORMATIVO),
                fatal ? " | FATAL: restaure el respaldo" : ""));
        sb.append(consistente() ? "Volumen consistente.\n" : "El volumen NO esta consistente.\n");
        return sb.toString();
    }
}
