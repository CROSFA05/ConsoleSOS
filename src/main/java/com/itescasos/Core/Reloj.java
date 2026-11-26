package com.itescasos.Core;

/**
 * Reloj de simulación: tiempo lógico en ticks compartido por el núcleo y los
 * subsistemas (por ejemplo, E/S). No hay esperas reales: el tiempo solo avanza
 * cuando alguien llama a {@link #avanzarA(int)}.
 */
public class Reloj {
    private int ahora;

    /** Crea un reloj en el instante 0. */
    public Reloj() {
        this(0);
    }

    /** Crea un reloj en un instante inicial dado. */
    public Reloj(int inicial) {
        if (inicial < 0) {
            throw new IllegalArgumentException("el tiempo inicial no puede ser negativo: " + inicial);
        }
        this.ahora = inicial;
    }

    /** Instante actual del reloj. */
    public int ahora() {
        return ahora;
    }

    /**
     * Avanza el reloj hasta {@code t}. El tiempo es monótono: si {@code t} es
     * menor o igual al instante actual, el reloj no se modifica.
     */
    public void avanzarA(int t) {
        if (t > ahora) {
            ahora = t;
        }
    }

    /** Avanza el reloj {@code ticks} ticks desde el instante actual. */
    public void avanzar(int ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("los ticks no pueden ser negativos: " + ticks);
        }
        avanzarA(ahora + ticks);
    }
}
