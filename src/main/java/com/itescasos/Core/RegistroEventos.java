package com.itescasos.Core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Registro común de eventos: tiempo del núcleo, módulo, sujeto, objeto y resultado. Nunca acepta secretos. */
public class RegistroEventos {
    private final Reloj reloj;
    private final Consumer<String> salida;
    private final List<String> lineas = new ArrayList<String>();

    public RegistroEventos(Reloj reloj, Consumer<String> salida) {
        this.reloj = reloj;
        this.salida = salida;
    }

    public void emitir(String modulo, String sujeto, String objeto, String evento, String resultado) {
        String linea = String.format("t=%d modulo=%s sujeto=%s objeto=%s evento=%s resultado=%s",
                reloj.ahora(), modulo,
                sujeto == null || sujeto.isEmpty() ? "-" : sujeto,
                objeto == null || objeto.isEmpty() ? "-" : objeto,
                evento, resultado == null || resultado.isEmpty() ? "-" : resultado);
        lineas.add(linea);
        if (salida != null) {
            salida.accept(linea);
        }
    }

    /** Texto libre (configuración, totales). No se usa para credenciales. */
    public void texto(String mensaje) {
        lineas.add(mensaje);
        if (salida != null) {
            salida.accept(mensaje);
        }
    }

    public List<String> lineas() {
        return new ArrayList<String>(lineas);
    }

    public void guardar(Path archivo) throws IOException {
        Files.write(archivo, lineas, StandardCharsets.UTF_8);
    }
}
