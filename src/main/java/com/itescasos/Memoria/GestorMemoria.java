package com.itescasos.Memoria;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

//Contiene el motor de paginación, la tabla de marcos físicos y los
// 4 algoritmos de reemplazo (FIFO, LRU, Óptimo y Reloj).
//Ahora es además un servicio con estado: los marcos guardan identidad (pid, página),
// cada PID tiene su propia tabla y los contadores globales se acumulan en referenciar.

/**
 * Administrador de memoria con estado real y API utilizable por un motor integrado.
 *
 * <p>Políticas admitidas: {@code fifo}, {@code lru}, {@code optimo}/{@code óptimo}
 * y {@code reloj} (alias {@code clock}), sin distinción de mayúsculas.</p>
 *
 * <p><b>Óptimo en línea:</b> {@link #referenciar(String, int, boolean)} no conoce
 * la cadena futura de referencias, por lo que, cuando la política es Óptimo,
 * utiliza <b>LRU como fallback</b> para elegir la víctima. {@link #simular}
 * recibe la cadena completa y aplica el algoritmo Óptimo exacto (offline);
 * si la cadena termina en la referencia actual, todos los marcos empatan como
 * "nunca usados" y se desempata por índice de marco.</p>
 *
 * <p>{@link #simular} y {@link #simularPaginacion} son corridas aisladas: no
 * modifican los marcos ni los contadores de esta instancia.</p>
 */
public class GestorMemoria {

    private enum Politica { FIFO, LRU, OPTIMO, RELOJ }

    private final int totalMarcos;
    private final int tamañoMarcoBytes;
    private final Marco[] marcos;
    private final Map<String, Map<Integer, EntradaTablaPaginas>> tablasPorPid;
    private final Map<String, EspacioProceso> espaciosPorPid;

    private Politica politica = Politica.FIFO;
    private long relojAccesos = 0L;
    private long siguienteOrdenCarga = 0L;
    private int punteroReloj = 0;
    private int fallosGlobales = 0;
    private int aciertosGlobales = 0;

    public GestorMemoria(int totalMarcos, int tamanoMarcoBytes) {
        if (totalMarcos <= 0) {
            throw new IllegalArgumentException("totalMarcos debe ser > 0");
        }
        if (tamanoMarcoBytes <= 0) {
            throw new IllegalArgumentException("tamanoMarcoBytes debe ser > 0");
        }
        this.totalMarcos = totalMarcos;
        this.tamañoMarcoBytes = tamanoMarcoBytes;
        this.marcos = nuevosMarcos();
        this.tablasPorPid = new HashMap<String, Map<Integer, EntradaTablaPaginas>>();
        this.espaciosPorPid = new HashMap<String, EspacioProceso>();
    }

    /** Cambia la política usada por {@link #referenciar}. No afecta a simulaciones aisladas. */
    public void setPolitica(String politica) {
        this.politica = parsearPolitica(politica);
    }

    /** Política activa (nombre en mayúsculas). */
    public String politica() {
        return politica.name();
    }

    public int totalMarcos() {
        return totalMarcos;
    }

    public int tamanoMarcoBytes() {
        return tamañoMarcoBytes;
    }

    public int fallos() {
        return fallosGlobales;
    }

    public int aciertos() {
        return aciertosGlobales;
    }

    public int marcosOcupados() {
        int ocupados = 0;
        for (int i = 0; i < marcos.length; i++) {
            if (!marcos[i].libre()) {
                ocupados++;
            }
        }
        return ocupados;
    }

    /** Estado de los marcos reales, p. ej. {@code [0=P1:0, 1=-, 2=P2:0]}. */
    public String estadoMarcos() {
        return formatearEstado(marcos);
    }

    /**
     * Declara el espacio de direcciones de un PID (número de páginas).
     * Opcional: si no se llama, el espacio conocido se infiere de las páginas
     * referenciadas y puede crecer con {@link #referenciar}.
     */
    public void asignarEspacio(String pid, int numeroPaginas) {
        validarPid(pid);
        if (numeroPaginas <= 0) {
            throw new IllegalArgumentException("numeroPaginas debe ser > 0");
        }
        EspacioProceso espacio = espaciosPorPid.get(pid);
        if (espacio == null) {
            espacio = new EspacioProceso();
            espaciosPorPid.put(pid, espacio);
        }
        espacio.declarar(numeroPaginas);
    }

    /**
     * Referencia una página en nombre de un PID.
     *
     * <p>Si la política activa es Óptimo, esta vía en línea no dispone de la
     * cadena futura y aplica LRU como fallback (documentado en el Javadoc de la clase).</p>
     *
     * @return {@code true} si hubo FALLO, {@code false} si fue acierto.
     * @throws IllegalArgumentException si {@code pid} es null, {@code pagina < 0}
     *         o la página excede un espacio declarado con {@link #asignarEspacio}.
     */
    public boolean referenciar(String pid, int pagina, boolean escritura) {
        validarPid(pid);
        if (pagina < 0) {
            throw new IllegalArgumentException("pagina debe ser >= 0");
        }
        observarPagina(pid, pagina);

        Map<Integer, EntradaTablaPaginas> tabla = tablaDelPid(pid);
        long instante = relojAccesos++;
        int residente = buscarMarco(marcos, pid, pagina);

        if (residente >= 0) {
            aciertosGlobales++;
            EntradaTablaPaginas entrada = entradaDe(tabla, pagina);
            entrada.registrarAcceso(escritura, instante);
            marcos[residente].registrarAcceso(instante, escritura);
            return false;
        }

        fallosGlobales++;
        int libre = buscarMarcoLibre(marcos);
        int destino;
        if (libre >= 0) {
            destino = libre;
        } else {
            destino = seleccionarVictimaEnVivo();
            liberarEntradaDelMarco(destino);
        }
        long orden = siguienteOrdenCarga++;
        marcos[destino].ocupar(pid, pagina, escritura, instante, orden);
        EntradaTablaPaginas entrada = entradaDe(tabla, pagina);
        entrada.registrarCarga(destino, instante, orden);
        entrada.registrarAcceso(escritura, instante);
        return true;
    }

    /**
     * Corrida offline aislada: los marcos empiezan vacíos y no se altera el estado
     * de esta instancia. Óptimo usa la cadena futura completa.
     */
    public Resultado simular(String pid, List<Integer> referencias, String politica) {
        return ejecutarSimulacion(pid, referencias, parsearPolitica(politica));
    }

    /**
     * Traduce una dirección lógica usando la tabla real del PID.
     *
     * @throws IllegalArgumentException si {@code pid} es null o {@code direccionLogica < 0}.
     * @throws IllegalStateException si la página no está residente (mensaje
     *         "página no residente"), está fuera del espacio conocido del PID,
     *         o el marco está fuera de rango.
     */
    public Traduccion traducir(String pid, int direccionLogica) {
        validarPid(pid);
        if (direccionLogica < 0) {
            throw new IllegalArgumentException("direccionLogica debe ser >= 0");
        }
        int pagina = direccionLogica / tamañoMarcoBytes;
        int desplazamiento = direccionLogica % tamañoMarcoBytes;

        EspacioProceso espacio = espaciosPorPid.get(pid);
        if (espacio != null && espacio.limiteConocido() && pagina >= espacio.limite()) {
            throw new IllegalStateException("página no residente: página fuera del espacio "
                    + "conocido del PID " + pid + " (página=" + pagina + ")");
        }

        Map<Integer, EntradaTablaPaginas> tabla = tablasPorPid.get(pid);
        EntradaTablaPaginas entrada = tabla == null ? null : tabla.get(pagina);
        if (entrada == null || !entrada.presente) {
            throw new IllegalStateException("página no residente (pid=" + pid + ", página=" + pagina + ")");
        }
        int marco = entrada.numeroMarco;
        if (!esMarcoValido(marco) || !pertenece(marco, pid, pagina)) {
            throw new IllegalStateException("página no residente: marco físico fuera de rango o con otra identidad ("
                    + marco + ")");
        }
        long direccionFisica = (long) marco * tamañoMarcoBytes + desplazamiento;
        return new Traduccion(pid, direccionLogica, pagina, desplazamiento, marco,
                direccionFisica, entrada.bitModificacion);
    }

    /** Libera todos los marcos y estructuras del PID. Los contadores globales se conservan. */
    public void liberar(String pid) {
        validarPid(pid);
        for (int i = 0; i < marcos.length; i++) {
            if (pid.equals(marcos[i].pid)) {
                marcos[i].liberar();
            }
        }
        tablasPorPid.remove(pid);
        espaciosPorPid.remove(pid);
    }

    // ------------------------------------------------------------------
    // Compatibilidad con la API que imprime por System.out.
    // ------------------------------------------------------------------

    //Simula una cadena de referencias de paginas y muestra la traza paso a paso
    public void simularPaginacion(List<Integer> referencias, String algoritmo) {
        if (referencias == null) {
            throw new IllegalArgumentException("referencias no puede ser null");
        }
        Politica politicaSimulada = parsearPolitica(algoritmo);
        Resultado resultado = ejecutarSimulacion("SIM", referencias, politicaSimulada);

        int fallosPagina = resultado.fallos();
        int aciertos = resultado.aciertos();

        System.out.println("\n============================================================");
        System.out.println(" ADMINISTRADOR DE MEMORIA - ALGORITMO: " + algoritmo.toUpperCase() + " (Marcos: "+ totalMarcos + ")");
        System.out.println("============================================================");
        System.out.printf("%-6s %-8s %-25s %-12s%n", "Paso", "Página", "Estado de Marcos", "Resultado");
        System.out.println("------------------------------------------------------------");

        for(int i = 0; i < referencias.size(); i++) {
            Resultado.Paso paso = resultado.pasos().get(i);
            System.out.printf("%-6d %-8d %-25s %-12s%n",
                    (i + 1), paso.pagina(), formatearMarcos(paso.marcosPaginas()), paso.fallo() ? "[FALLO]" : "Acierto");
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

    //Traduce una direccion logica (virtual) a direccion fisica y calcula fragmentacion interna
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

    // ------------------------------------------------------------------
    // Núcleo de simulación (sin efectos sobre el estado de la instancia).
    // ------------------------------------------------------------------

    private Resultado ejecutarSimulacion(String pid, List<Integer> referencias, Politica politicaSimulada) {
        if (pid == null) {
            throw new IllegalArgumentException("pid no puede ser null");
        }
        if (referencias == null) {
            throw new IllegalArgumentException("referencias no puede ser null");
        }

        Marco[] marcosLocales = nuevosMarcos();
        long reloj = 0L;
        long orden = 0L;
        int puntero = 0;
        int fallos = 0;
        int aciertos = 0;
        List<Resultado.Paso> pasos = new ArrayList<Resultado.Paso>();

        for (int i = 0; i < referencias.size(); i++) {
            int paginaActual = referencias.get(i);
            int residente = buscarMarco(marcosLocales, pid, paginaActual);
            boolean esFallo;
            int marcoUsado;

            if (residente >= 0) {
                aciertos++;
                esFallo = false;
                marcoUsado = residente;
                marcosLocales[residente].registrarAcceso(i, false);
            } else {
                fallos++;
                esFallo = true;
                int marcoLibre = buscarMarcoLibre(marcosLocales);
                int destino;
                if (marcoLibre >= 0) {
                    destino = marcoLibre;
                } else if (politicaSimulada == Politica.FIFO) {
                    destino = seleccionarPorOrdenCarga(marcosLocales);
                } else if (politicaSimulada == Politica.LRU) {
                    destino = seleccionarPorUltimoUso(marcosLocales);
                } else if (politicaSimulada == Politica.OPTIMO) {
                    destino = seleccionarVictimaOptimo(marcosLocales, referencias, i);
                } else {
                    int[] punteroSalida = new int[] { puntero };
                    destino = seleccionarReloj(marcosLocales, punteroSalida);
                    puntero = punteroSalida[0];
                }
                marcosLocales[destino].ocupar(pid, paginaActual, false, i, orden++);
                marcoUsado = destino;
            }

            pasos.add(new Resultado.Paso(i + 1, paginaActual, paginasDe(marcosLocales),
                    formatearEstado(marcosLocales), esFallo, marcoUsado));
        }

        double total = referencias.size();
        double tasaFallos = total == 0.0 ? 0.0 : (fallos * 100.0) / total;
        double tasaAciertos = total == 0.0 ? 0.0 : (aciertos * 100.0) / total;
        return new Resultado(pid, politicaSimulada.name(), referencias.size(), fallos, aciertos,
                tasaFallos, tasaAciertos, pasos);
    }

    // ------------------------------------------------------------------
    // Estructuras y ayudantes de estado real.
    // ------------------------------------------------------------------

    private Marco[] nuevosMarcos() {
        Marco[] resultado = new Marco[totalMarcos];
        for (int i = 0; i < resultado.length; i++) {
            resultado[i] = new Marco();
        }
        return resultado;
    }

    private Map<Integer, EntradaTablaPaginas> tablaDelPid(String pid) {
        Map<Integer, EntradaTablaPaginas> tabla = tablasPorPid.get(pid);
        if (tabla == null) {
            tabla = new HashMap<Integer, EntradaTablaPaginas>();
            tablasPorPid.put(pid, tabla);
        }
        return tabla;
    }

    private static EntradaTablaPaginas entradaDe(Map<Integer, EntradaTablaPaginas> tabla, int pagina) {
        EntradaTablaPaginas entrada = tabla.get(pagina);
        if (entrada == null) {
            entrada = new EntradaTablaPaginas(pagina);
            tabla.put(pagina, entrada);
        }
        return entrada;
    }

    private void observarPagina(String pid, int pagina) {
        EspacioProceso espacio = espaciosPorPid.get(pid);
        if (espacio == null) {
            espacio = new EspacioProceso();
            espaciosPorPid.put(pid, espacio);
        }
        if (espacio.limiteExplicito() != null && pagina >= espacio.limiteExplicito().intValue()) {
            throw new IllegalArgumentException("pagina fuera del espacio asignado al PID " + pid
                    + " (pagina=" + pagina + ", limite=" + espacio.limiteExplicito() + ")");
        }
        espacio.observar(pagina);
    }

    private void liberarEntradaDelMarco(int indiceMarco) {
        Marco marco = marcos[indiceMarco];
        if (marco.pid == null) {
            return;
        }
        Map<Integer, EntradaTablaPaginas> tabla = tablasPorPid.get(marco.pid);
        if (tabla == null) {
            return;
        }
        EntradaTablaPaginas entrada = tabla.get(marco.pagina);
        if (entrada != null && entrada.numeroMarco == indiceMarco) {
            entrada.liberar();
            tabla.remove(marco.pagina);
        }
    }

    private int seleccionarVictimaEnVivo() {
        if (politica == Politica.FIFO) {
            return seleccionarPorOrdenCarga(marcos);
        }
        if (politica == Politica.RELOJ) {
            int[] punteroSalida = new int[] { punteroReloj };
            int victima = seleccionarReloj(marcos, punteroSalida);
            punteroReloj = punteroSalida[0];
            return victima;
        }
        // Óptimo en línea no tiene futuro: fallback LRU documentado en la clase.
        return seleccionarPorUltimoUso(marcos);
    }

    private boolean esMarcoValido(int indice) {
        return indice >= 0 && indice < marcos.length;
    }

    private boolean pertenece(int indice, String pid, int pagina) {
        Marco marco = marcos[indice];
        return pid.equals(marco.pid) && marco.pagina == pagina;
    }

    private static int buscarMarco(Marco[] marcosLocales, String pid, int pagina) {
        for (int i = 0; i < marcosLocales.length; i++) {
            Marco marco = marcosLocales[i];
            if (pid.equals(marco.pid) && marco.pagina == pagina) {
                return i;
            }
        }
        return -1;
    }

    private static int buscarMarcoLibre(Marco[] marcosLocales) {
        for (int i = 0; i < marcosLocales.length; i++) {
            if (marcosLocales[i].libre()) {
                return i;
            }
        }
        return -1;
    }

    private static int seleccionarPorOrdenCarga(Marco[] marcosLocales) {
        int victima = 0;
        long menorOrden = Long.MAX_VALUE;
        for (int i = 0; i < marcosLocales.length; i++) {
            Marco marco = marcosLocales[i];
            if (!marco.libre() && marco.ordenCarga < menorOrden) {
                menorOrden = marco.ordenCarga;
                victima = i;
            }
        }
        return victima;
    }

    private static int seleccionarPorUltimoUso(Marco[] marcosLocales) {
        int victima = 0;
        long menorUso = Long.MAX_VALUE;
        for (int i = 0; i < marcosLocales.length; i++) {
            Marco marco = marcosLocales[i];
            if (!marco.libre() && marco.ultimoUso < menorUso) {
                menorUso = marco.ultimoUso;
                victima = i;
            }
        }
        return victima;
    }

    // El future completo se recorre desde indiceActual + 1; el marco cuyo
    // siguiente uso esté más lejano (o nunca aparezca) es la víctima.
    private static int seleccionarVictimaOptimo(Marco[] marcosLocales, List<Integer> referencias, int indiceActual) {
        int victima = 0;
        int peorDistancia = -1;
        for (int i = 0; i < marcosLocales.length; i++) {
            int paginaEnMarco = marcosLocales[i].pagina;
            int siguienteUso = Integer.MAX_VALUE;
            for (int j = indiceActual + 1; j < referencias.size(); j++) {
                Integer referencia = referencias.get(j);
                if (referencia != null && referencia.intValue() == paginaEnMarco) {
                    siguienteUso = j;
                    break;
                }
            }
            if (siguienteUso > peorDistancia) {
                peorDistancia = siguienteUso;
                victima = i;
            }
        }
        return victima;
    }

    // Actualiza puntero[0] para que apunte al marco siguiente de la víctima.
    private static int seleccionarReloj(Marco[] marcosLocales, int[] puntero) {
        int actual = ((puntero[0] % marcosLocales.length) + marcosLocales.length) % marcosLocales.length;
        while (true) {
            if (!marcosLocales[actual].bitReferencia) {
                puntero[0] = (actual + 1) % marcosLocales.length;
                return actual;
            }
            marcosLocales[actual].bitReferencia = false;
            actual = (actual + 1) % marcosLocales.length;
        }
    }

    private static int[] paginasDe(Marco[] marcosLocales) {
        int[] paginas = new int[marcosLocales.length];
        for (int i = 0; i < paginas.length; i++) {
            paginas[i] = marcosLocales[i].libre() ? -1 : marcosLocales[i].pagina;
        }
        return paginas;
    }

    private static String formatearEstado(Marco[] marcosLocales) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < marcosLocales.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(i).append('=');
            Marco marco = marcosLocales[i];
            if (marco.libre()) {
                sb.append('-');
            } else {
                sb.append(marco.pid).append(':').append(marco.pagina);
            }
        }
        return sb.append(']').toString();
    }

    private static String formatearMarcos(int[] marcos) {
        StringBuilder sb = new StringBuilder("[ ");
        for(int m : marcos) {
            sb.append(m == -1 ? "-" : m).append(" ");
        }
        sb.append("]");
        return sb.toString();
    }

    private static void validarPid(String pid) {
        if (pid == null) {
            throw new IllegalArgumentException("pid no puede ser null");
        }
    }

    private static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String resultado = texto.trim().toLowerCase(Locale.ROOT);
        resultado = resultado.replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u").replace("ñ", "n");
        return resultado;
    }

    private static Politica parsearPolitica(String texto) {
        String nombre = normalizar(texto);
        if ("fifo".equals(nombre)) {
            return Politica.FIFO;
        }
        if ("lru".equals(nombre)) {
            return Politica.LRU;
        }
        if ("optimo".equals(nombre)) {
            return Politica.OPTIMO;
        }
        if ("reloj".equals(nombre) || "clock".equals(nombre)) {
            return Politica.RELOJ;
        }
        throw new IllegalArgumentException("Política no soportada: " + texto
                + " (use fifo, lru, optimo/óptimo o reloj)");
    }

    private static final class Marco {
        private String pid;
        private int pagina = -1;
        private boolean bitReferencia;
        private boolean bitModificacion;
        private long ultimoUso = -1L;
        private long ordenCarga = -1L;

        boolean libre() {
            return pid == null;
        }

        void ocupar(String pid, int pagina, boolean escritura, long instante, long orden) {
            this.pid = pid;
            this.pagina = pagina;
            this.ordenCarga = orden;
            registrarAcceso(instante, escritura);
        }

        void registrarAcceso(long instante, boolean escritura) {
            this.bitReferencia = true;
            this.ultimoUso = instante;
            if (escritura) {
                this.bitModificacion = true;
            }
        }

        void liberar() {
            this.pid = null;
            this.pagina = -1;
            this.bitReferencia = false;
            this.bitModificacion = false;
            this.ultimoUso = -1L;
            this.ordenCarga = -1L;
        }
    }

    private static final class EspacioProceso {
        private Integer limiteExplicito;
        private int maxPaginaVista = -1;

        Integer limiteExplicito() {
            return limiteExplicito;
        }

        boolean limiteConocido() {
            return limiteExplicito != null || maxPaginaVista >= 0;
        }

        int limite() {
            return limiteExplicito != null ? limiteExplicito.intValue() : maxPaginaVista + 1;
        }

        void observar(int pagina) {
            if (pagina > maxPaginaVista) {
                maxPaginaVista = pagina;
            }
        }

        void declarar(int numeroPaginas) {
            if (limiteExplicito != null && numeroPaginas < limiteExplicito.intValue()) {
                throw new IllegalArgumentException("no se puede reducir el espacio asignado del PID");
            }
            if (maxPaginaVista >= numeroPaginas) {
                throw new IllegalArgumentException("numeroPaginas insuficiente para las páginas ya referenciadas");
            }
            limiteExplicito = Integer.valueOf(numeroPaginas);
        }
    }

    // ------------------------------------------------------------------
    // Resultados públicos.
    // ------------------------------------------------------------------

    /** Resultado inmutable de una simulación offline. */
    public static final class Resultado {
        private final String pid;
        private final String politica;
        private final int totalReferencias;
        private final int fallos;
        private final int aciertos;
        private final double tasaFallos;
        private final double tasaAciertos;
        private final List<Paso> pasos;

        private Resultado(String pid, String politica, int totalReferencias, int fallos, int aciertos,
                          double tasaFallos, double tasaAciertos, List<Paso> pasos) {
            this.pid = pid;
            this.politica = politica;
            this.totalReferencias = totalReferencias;
            this.fallos = fallos;
            this.aciertos = aciertos;
            this.tasaFallos = tasaFallos;
            this.tasaAciertos = tasaAciertos;
            this.pasos = Collections.unmodifiableList(new ArrayList<Paso>(pasos));
        }

        public String pid() { return pid; }
        public String politica() { return politica; }
        public int totalReferencias() { return totalReferencias; }
        public int fallos() { return fallos; }
        public int aciertos() { return aciertos; }
        public double tasaFallos() { return tasaFallos; }
        public double tasaAciertos() { return tasaAciertos; }
        public List<Paso> pasos() { return pasos; }

        /** Traza tabulada de todos los pasos. */
        public String tabla() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%-6s %-8s %-30s %-12s %s%n",
                    "Paso", "Página", "Estado de Marcos", "Resultado", "Marco"));
            sb.append("------------------------------------------------------------").append(System.lineSeparator());
            for (Paso paso : pasos) {
                sb.append(String.format(Locale.ROOT, "%-6d %-8d %-30s %-12s %d%n",
                        paso.numero(), paso.pagina(), paso.estadoMarcos(),
                        paso.fallo() ? "[FALLO]" : "Acierto", paso.marcoAsignado()));
            }
            sb.append("------------------------------------------------------------");
            return sb.toString();
        }

        /** Totales, tasas y aviso de thrashing. */
        public String resumen() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, " Total Referencias: %d | Fallos: %d | Aciertos: %d%n",
                    totalReferencias, fallos, aciertos));
            sb.append(String.format(Locale.ROOT, " Tasa de Fallos:    %.2f%% | Hit Ratio: %.2f%%",
                    tasaFallos, tasaAciertos));
            if (tasaFallos > 60.0) {
                sb.append(System.lineSeparator())
                        .append(" [ALERTA DE SISTEMA]: Posible THRASHING detectado (tasa de fallos crítica).");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return tabla() + System.lineSeparator() + resumen();
        }

        /** Un paso de la simulación. */
        public static final class Paso {
            private final int numero;
            private final int pagina;
            private final int[] marcosPorPagina;
            private final String estadoMarcos;
            private final boolean fallo;
            private final int marcoAsignado;

            private Paso(int numero, int pagina, int[] marcosPorPagina, String estadoMarcos,
                         boolean fallo, int marcoAsignado) {
                this.numero = numero;
                this.pagina = pagina;
                this.marcosPorPagina = Arrays.copyOf(marcosPorPagina, marcosPorPagina.length);
                this.estadoMarcos = estadoMarcos;
                this.fallo = fallo;
                this.marcoAsignado = marcoAsignado;
            }

            public int numero() { return numero; }
            public int pagina() { return pagina; }
            public int[] marcosPaginas() { return Arrays.copyOf(marcosPorPagina, marcosPorPagina.length); }
            public String estadoMarcos() { return estadoMarcos; }
            public boolean fallo() { return fallo; }
            public boolean acierto() { return !fallo; }
            public int marcoAsignado() { return marcoAsignado; }

            @Override
            public String toString() {
                return String.format(Locale.ROOT,
                        "Paso=%d, Página=%d, Marcos=%s, %s, Marco=%d",
                        numero, pagina, estadoMarcos, fallo ? "[FALLO]" : "Acierto", marcoAsignado);
            }
        }
    }

    /** Traducción MMU de una dirección lógica residente. */
    public static final class Traduccion {
        private final String pid;
        private final int direccionLogica;
        private final int pagina;
        private final int desplazamiento;
        private final int marco;
        private final long direccionFisica;
        private final boolean paginaModificada;

        private Traduccion(String pid, int direccionLogica, int pagina, int desplazamiento,
                           int marco, long direccionFisica, boolean paginaModificada) {
            this.pid = pid;
            this.direccionLogica = direccionLogica;
            this.pagina = pagina;
            this.desplazamiento = desplazamiento;
            this.marco = marco;
            this.direccionFisica = direccionFisica;
            this.paginaModificada = paginaModificada;
        }

        public String pid() { return pid; }
        public int direccionLogica() { return direccionLogica; }
        public int pagina() { return pagina; }
        public int desplazamiento() { return desplazamiento; }
        public int marco() { return marco; }
        public long direccionFisica() { return direccionFisica; }
        public boolean paginaModificada() { return paginaModificada; }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "Traduccion{pid=%s, logica=%d -> pagina=%d, offset=%d, marco=%d, fisica=%d, modificada=%s}",
                    pid, direccionLogica, pagina, desplazamiento, marco, direccionFisica, paginaModificada);
        }
    }
}
