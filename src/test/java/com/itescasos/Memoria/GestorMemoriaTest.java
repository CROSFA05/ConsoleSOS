package com.itescasos.Memoria;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class GestorMemoriaTest {

    private static final List<Integer> CADA_CONTROL = Arrays.asList(0, 1, 2, 0, 3, 1, 4, 0);

    @Test
    public void casoDeControlDeLaGuia() {
        GestorMemoria memoria = new GestorMemoria(3, 4096);

        GestorMemoria.Resultado fifo = memoria.simular("P1", CADA_CONTROL, "FIFO");
        assertEquals(6, fifo.fallos());
        assertEquals(2, fifo.aciertos());
        assertEquals(75.0, fifo.tasaFallos(), 0.0001);
        assertEquals(8, fifo.pasos().size());
        assertEquals(1, fifo.pasos().get(0).numero());
        assertEquals(0, fifo.pasos().get(0).marcoAsignado());
        assertTrue(fifo.pasos().get(3).acierto());
        assertTrue(fifo.tabla().contains("Paso"));
        assertTrue(fifo.resumen().contains("Fallos: 6"));

        assertEquals(7, memoria.simular("P1", CADA_CONTROL, "LRU").fallos());
        assertEquals(5, memoria.simular("P1", CADA_CONTROL, "ÓPTIMO").fallos());
        assertEquals(5, memoria.simular("P1", CADA_CONTROL, "optimo").fallos());
        assertEquals(6, memoria.simular("P1", CADA_CONTROL, "RELOJ").fallos());
    }

    @Test
    public void setPoliticaAceptaTildeYCaso() {
        GestorMemoria memoria = new GestorMemoria(2, 1024);
        memoria.setPolitica("ÓPTIMO");
        assertEquals("OPTIMO", memoria.politica());
        memoria.setPolitica("optimo");
        assertEquals("OPTIMO", memoria.politica());
        memoria.setPolitica(" ReLoJ ");
        assertEquals("RELOJ", memoria.politica());
        memoria.setPolitica("fifo");
        assertEquals("FIFO", memoria.politica());
        try {
            memoria.setPolitica("desconocida");
            fail("Debe rechazar una política desconocida");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("Política no soportada"));
        }
    }

    @Test
    public void marcosConservanIdentidadPorPid() {
        GestorMemoria memoria = new GestorMemoria(2, 4096);

        assertTrue(memoria.referenciar("A", 0, false));
        assertTrue(memoria.referenciar("B", 0, false));

        assertEquals(0, memoria.traducir("A", 0).marco());
        assertEquals(1, memoria.traducir("B", 0).marco());
        assertEquals(2, memoria.marcosOcupados());

        // Con FIFO el marco más antiguo es el de A; B no debe verse afectado.
        assertTrue(memoria.referenciar("C", 0, false));
        assertEquals(1, memoria.traducir("B", 0).marco());
        try {
            memoria.traducir("A", 0);
            fail("A fue expulsado");
        } catch (IllegalStateException esperado) {
            assertTrue(esperado.getMessage().contains("página no residente"));
        }
        assertEquals(3, memoria.fallos());
        assertEquals(0, memoria.aciertos());
        assertTrue(memoria.estadoMarcos().contains("C:0"));
    }

    @Test
    public void liberarLiberaMarcosYEstructurasDelPid() {
        GestorMemoria memoria = new GestorMemoria(2, 4096);
        memoria.referenciar("A", 0, false);
        memoria.referenciar("A", 1, false);
        assertEquals(2, memoria.marcosOcupados());

        memoria.liberar("A");
        assertEquals(0, memoria.marcosOcupados());
        assertEquals(2, memoria.fallos());
        try {
            memoria.traducir("A", 0);
            fail("El PID fue liberado");
        } catch (IllegalStateException esperado) {
            assertTrue(esperado.getMessage().contains("página no residente"));
        }

        assertTrue(memoria.referenciar("B", 0, false));
        assertEquals(0, memoria.traducir("B", 0).marco());
        assertEquals(1, memoria.marcosOcupados());
    }

    @Test
    public void traducirCalculaPaginaOffsetYDireccionFisica() {
        GestorMemoria memoria = new GestorMemoria(2, 4096);
        assertTrue(memoria.referenciar("P", 2, true));

        int logica = 2 * 4096 + 17;
        GestorMemoria.Traduccion t = memoria.traducir("P", logica);
        assertEquals(logica, t.direccionLogica());
        assertEquals(2, t.pagina());
        assertEquals(17, t.desplazamiento());
        assertEquals(0, t.marco());
        assertEquals(17L, t.direccionFisica());
        assertTrue(t.paginaModificada());
        assertTrue(t.toString().contains("fisica=17"));

        try {
            memoria.traducir("P", 3 * 4096);
            fail("La página 3 no está residente");
        } catch (IllegalStateException esperado) {
            assertTrue(esperado.getMessage().contains("página no residente"));
        }
    }

    @Test
    public void validaConstructorYOpciones() {
        try {
            new GestorMemoria(0, 4096);
            fail("totalMarcos debe ser positivo");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("totalMarcos"));
        }
        try {
            new GestorMemoria(2, -1);
            fail("tamanoMarcoBytes debe ser positivo");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("tamanoMarcoBytes"));
        }

        GestorMemoria memoria = new GestorMemoria(2, 4096);
        try {
            memoria.referenciar(null, 0, false);
            fail("pid null");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("pid"));
        }
        try {
            memoria.referenciar("P", -1, false);
            fail("pagina negativa");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("pagina"));
        }
        try {
            memoria.traducir("P", -1);
            fail("dirección negativa");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("direccionLogica"));
        }

        memoria.asignarEspacio("Q", 1);
        try {
            memoria.referenciar("Q", 1, false);
            fail("Página fuera del espacio declarado");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("espacio asignado"));
        }
    }

    @Test
    public void optimoEnLineaUsaFallbackLru() {
        GestorMemoria memoria = new GestorMemoria(2, 4096);
        memoria.setPolitica("óptimo");

        assertTrue(memoria.referenciar("P", 0, false));
        assertTrue(memoria.referenciar("P", 1, false));
        assertFalse(memoria.referenciar("P", 0, false));
        assertTrue(memoria.referenciar("P", 2, false));

        assertEquals(3, memoria.fallos());
        assertEquals(1, memoria.aciertos());
        assertEquals(0, memoria.traducir("P", 0).marco());
        try {
            memoria.traducir("P", 4096);
            fail("LRU debía expulsar la página 1");
        } catch (IllegalStateException esperado) {
            assertTrue(esperado.getMessage().contains("página no residente"));
        }
    }

    @Test
    public void simularEsAisladoRespectoAlEstadoReal() {
        GestorMemoria memoria = new GestorMemoria(3, 4096);
        memoria.simular("P", CADA_CONTROL, "FIFO");

        assertEquals(0, memoria.fallos());
        assertEquals(0, memoria.aciertos());
        assertEquals(0, memoria.marcosOcupados());
        assertTrue(memoria.estadoMarcos().contains("-"));
    }

    @Test
    public void entradaTablaPaginasRegistraBits() {
        EntradaTablaPaginas entrada = new EntradaTablaPaginas(4);
        assertFalse(entrada.presente);
        assertFalse(entrada.bitModificacion);

        entrada.registrarCarga(2, 10L, 7L);
        entrada.registrarAcceso(true, 11L);
        assertTrue(entrada.presente);
        assertTrue(entrada.residenteEn(2));
        assertTrue(entrada.bitReferencia);
        assertTrue(entrada.bitModificacion);
        assertEquals(11L, entrada.ultimoAcceso);
        assertEquals(7L, entrada.ordenCarga);

        entrada.liberar();
        assertFalse(entrada.presente);
        assertEquals(-1, entrada.numeroMarco);
        assertEquals(-1L, entrada.ordenCarga);
    }

    @Test
    public void metodosLegacySiguenImprimiendo() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, "UTF-8"));
            GestorMemoria memoria = new GestorMemoria(3, 4096);
            memoria.simularPaginacion(CADA_CONTROL, "FIFO");
            memoria.traducirDireccionYFragmentacion(10000, 5000, new int[] {0, -1, 2});
        } finally {
            System.setOut(original);
        }

        String salida = buffer.toString("UTF-8");
        assertTrue(salida.contains("ALGORITMO: FIFO"));
        assertTrue(salida.contains("Fallos: 6"));
        assertTrue(salida.contains("THRASHING"));
        assertTrue(salida.contains("TRADUCCIÓN MMU Y FRAGMENTACIÓN"));
        assertTrue(salida.contains("Fragmentación interna:   2288 bytes"));
    }
}
