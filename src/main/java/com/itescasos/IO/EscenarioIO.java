package com.itescasos.IO;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sección [io] de un escenario. Formato (las líneas que empiezan con # son comentarios):
 *
 *   [io]
 *   politica = scan            # fcfs | scan | cscan | todas   (todas = compara las tres)
 *   cilindros = 100            # el disco tiene cilindros * bloquesPorCilindro bloques
 *   bloquesPorCilindro = 1
 *   bloqueBytes = 128
 *   cabezal = 40               # cilindro inicial
 *   direccion = asc            # asc | desc
 *   cilindrosPorTick = 10      # modelo de latencia: ceil(distancia/cilindrosPorTick) + 1 tick
 *   terminal.entrada = hola    # línea de teclado simulado (puede repetirse)
 *   R1, t=0, pid=P1, op=leer, destino=10                   # disco (por defecto)
 *   R5, t=2, pid=P2, dispositivo=terminal, op=escribir, texto="hola"
 *
 * Campos de solicitud: t (def. 0), pid, dispositivo (def. disco), op (leer|escribir),
 * destino (def. 0), tamano (def. bloque en disco, largo del texto en terminal),
 * modo (sinc|asinc, def. sinc), texto (terminal, escribir).
 */
public class EscenarioIO {
    /** Una solicitud tal como se declara en el archivo. */
    public static class Peticion {
        public final String etiqueta;
        public final int t;
        public final String pid;
        public final String dispositivo;
        public final Operacion op;
        public final int destino;
        public final int tamano;
        public final ModoES modo;
        public final byte[] datos;
        public final int linea;

        Peticion(String etiqueta, int t, String pid, String dispositivo, Operacion op, int destino,
                 int tamano, ModoES modo, byte[] datos, int linea) {
            this.etiqueta = etiqueta;
            this.t = t;
            this.pid = pid;
            this.dispositivo = dispositivo;
            this.op = op;
            this.destino = destino;
            this.tamano = tamano;
            this.modo = modo;
            this.datos = datos;
            this.linea = linea;
        }
    }

    public List<PoliticaDisco> politicas = new ArrayList<PoliticaDisco>(Arrays.asList(PoliticaDisco.FCFS));
    public int cilindros = 100;
    public int bloquesPorCilindro = 1;
    public int bloqueBytes = 128;
    public int cabezal = 0;
    public Direccion direccion = Direccion.ASCENDENTE;
    public int cilindrosPorTick = 10;
    public final List<String> entradaTerminal = new ArrayList<String>();
    public final List<Peticion> peticiones = new ArrayList<Peticion>();

    public static EscenarioIO leer(Path archivo) throws java.io.IOException {
        return analizar(Files.readAllLines(archivo, StandardCharsets.UTF_8));
    }

    public static EscenarioIO analizar(List<String> lineas) {
        EscenarioIO e = new EscenarioIO();
        boolean enIo = false;
        boolean hayIo = false;
        Set<String> etiquetas = new HashSet<String>();
        int n = 0;
        for (String cruda : lineas) {
            n++;
            String l = quitarComentario(cruda).trim();
            if (l.isEmpty()) {
                continue;
            }
            if (l.startsWith("[")) {
                if (!l.endsWith("]")) {
                    throw new ErrorEscenario(n, "sección mal cerrada: " + l);
                }
                String nombre = l.substring(1, l.length() - 1).trim().toLowerCase(Locale.ROOT);
                enIo = nombre.equals("io");
                hayIo |= enIo;
                continue; // otras secciones pertenecen a otros módulos
            }
            if (!enIo) {
                continue;
            }
            List<String> campos = dividir(l, n);
            if (campos.get(0).indexOf('=') < 0) {
                e.peticion(campos, n, etiquetas);
            } else if (campos.size() == 1) {
                e.configuracion(campos.get(0), n);
            } else {
                throw new ErrorEscenario(n, "línea ambigua (¿falta una etiqueta de solicitud antes de la coma?): " + l);
            }
        }
        if (!hayIo) {
            throw new ErrorEscenario(0, "el escenario no tiene sección [io]");
        }
        e.validarGlobal();
        return e;
    }

    private void configuracion(String par, int n) {
        int i = par.indexOf('=');
        String k = par.substring(0, i).trim();
        String v = par.substring(i + 1).trim();
        switch (k) {
            case "politica":
                politicas = new ArrayList<PoliticaDisco>();
                String vv = v.toLowerCase(Locale.ROOT);
                if (vv.equals("todas")) {
                    politicas.addAll(Arrays.asList(PoliticaDisco.FCFS, PoliticaDisco.SCAN, PoliticaDisco.CSCAN));
                } else if (vv.equals("fcfs")) {
                    politicas.add(PoliticaDisco.FCFS);
                } else if (vv.equals("scan")) {
                    politicas.add(PoliticaDisco.SCAN);
                } else if (vv.equals("cscan") || vv.equals("c-scan")) {
                    politicas.add(PoliticaDisco.CSCAN);
                } else {
                    throw new ErrorEscenario(n, "politica desconocida '" + v + "' (fcfs, scan, cscan, todas)");
                }
                break;
            case "cilindros": cilindros = entero(v, n, k, 1); break;
            case "bloquesPorCilindro": bloquesPorCilindro = entero(v, n, k, 1); break;
            case "bloqueBytes": bloqueBytes = entero(v, n, k, 1); break;
            case "cabezal": cabezal = entero(v, n, k, 0); break;
            case "cilindrosPorTick": cilindrosPorTick = entero(v, n, k, 1); break;
            case "direccion":
                String d = v.toLowerCase(Locale.ROOT);
                if (d.equals("asc")) {
                    direccion = Direccion.ASCENDENTE;
                } else if (d.equals("desc")) {
                    direccion = Direccion.DESCENDENTE;
                } else {
                    throw new ErrorEscenario(n, "direccion debe ser asc o desc");
                }
                break;
            case "terminal.entrada": entradaTerminal.add(quitarComillas(v)); break;
            default:
                throw new ErrorEscenario(n, "clave desconocida en [io]: '" + k + "'");
        }
    }

    private void peticion(List<String> campos, int n, Set<String> etiquetas) {
        String etiqueta = campos.get(0);
        if (!etiquetas.add(etiqueta)) {
            throw new ErrorEscenario(n, "identificador de solicitud duplicado: " + etiqueta);
        }
        Map<String, String> kv = new LinkedHashMap<String, String>();
        for (int i = 1; i < campos.size(); i++) {
            String c = campos.get(i);
            int p = c.indexOf('=');
            if (p < 0) {
                throw new ErrorEscenario(n, "se esperaba clave=valor y se encontró '" + c + "'");
            }
            String k = c.substring(0, p).trim();
            if (kv.put(k, c.substring(p + 1).trim()) != null) {
                throw new ErrorEscenario(n, "campo repetido: " + k);
            }
        }
        for (String k : kv.keySet()) {
            if (!Arrays.asList("t", "pid", "dispositivo", "op", "destino", "tamano", "modo", "texto").contains(k)) {
                throw new ErrorEscenario(n, "campo desconocido: " + k);
            }
        }
        int t = kv.containsKey("t") ? entero(kv.get("t"), n, "t", 0) : 0;
        String pid = kv.containsKey("pid") ? kv.get("pid") : "-";
        String disp = kv.containsKey("dispositivo") ? kv.get("dispositivo").toLowerCase(Locale.ROOT) : DiscoSimulado.DISCO;
        if (!disp.equals(DiscoSimulado.DISCO) && !disp.equals(DiscoSimulado.TERMINAL)) {
            throw new ErrorEscenario(n, "dispositivo desconocido: " + disp + " (disco, terminal)");
        }
        if (!kv.containsKey("op")) {
            throw new ErrorEscenario(n, "falta op=leer|escribir");
        }
        Operacion op;
        String o = kv.get("op").toLowerCase(Locale.ROOT);
        if (o.equals("leer")) {
            op = Operacion.LEER;
        } else if (o.equals("escribir")) {
            op = Operacion.ESCRIBIR;
        } else {
            throw new ErrorEscenario(n, "op debe ser leer o escribir");
        }
        int destino = kv.containsKey("destino") ? entero(kv.get("destino"), n, "destino", 0) : 0;
        ModoES modo = ModoES.SINCRONA;
        if (kv.containsKey("modo")) {
            String m = kv.get("modo").toLowerCase(Locale.ROOT);
            if (m.startsWith("asinc")) {
                modo = ModoES.ASINCRONA;
            } else if (!m.startsWith("sinc")) {
                throw new ErrorEscenario(n, "modo debe ser sinc o asinc");
            }
        }
        byte[] datos = null;
        int tamano;
        if (disp.equals(DiscoSimulado.TERMINAL) && op == Operacion.ESCRIBIR) {
            if (!kv.containsKey("texto")) {
                throw new ErrorEscenario(n, "escribir en la terminal requiere texto=\"...\"");
            }
            datos = quitarComillas(kv.get("texto")).getBytes(StandardCharsets.UTF_8);
            tamano = kv.containsKey("tamano") ? entero(kv.get("tamano"), n, "tamano", 1) : datos.length;
            if (tamano != datos.length) {
                throw new ErrorEscenario(n, "tamano (" + tamano + ") no coincide con el texto (" + datos.length + " bytes)");
            }
        } else {
            if (kv.containsKey("texto")) {
                throw new ErrorEscenario(n, "texto solo aplica a escribir en la terminal");
            }
            tamano = kv.containsKey("tamano") ? entero(kv.get("tamano"), n, "tamano", 1)
                    : (disp.equals(DiscoSimulado.DISCO) ? bloqueBytes : 16);
            if (op == Operacion.ESCRIBIR) { // disco: contenido de prueba reproducible
                datos = new byte[tamano];
                Arrays.fill(datos, (byte) ('A' + (n % 26)));
            }
        }
        peticiones.add(new Peticion(etiqueta, t, pid, disp, op, destino, tamano, modo, datos, n));
    }

    /**
     * Las peticiones fuera de rango NO se rechazan aquí: llegan al driver y terminan FALLIDAS
     * (así se puede demostrar el rechazo con su resultado). Aquí solo se valida la coherencia global.
     */
    private void validarGlobal() {
        if (cabezal >= cilindros) {
            throw new ErrorEscenario(0, "cabezal (" + cabezal + ") fuera de [0," + (cilindros - 1) + "]");
        }
    }

    // ------------------------------------------------------------ utilidades de análisis

    static String quitarComentario(String l) {
        boolean comillas = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') {
                comillas = !comillas;
            } else if (c == '#' && !comillas) {
                return l.substring(0, i);
            }
        }
        return l;
    }

    /** Divide por comas respetando comillas dobles. */
    static List<String> dividir(String l, int n) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') {
                comillas = !comillas;
                sb.append(c);
            } else if (c == ',' && !comillas) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (comillas) {
            throw new ErrorEscenario(n, "comillas sin cerrar");
        }
        out.add(sb.toString().trim());
        return out;
    }

    static String quitarComillas(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static int entero(String v, int n, String campo, int minimo) {
        try {
            int x = Integer.parseInt(v.trim());
            if (x < minimo) {
                throw new ErrorEscenario(n, campo + " debe ser >= " + minimo + " (recibido " + x + ")");
            }
            return x;
        } catch (NumberFormatException ex) {
            throw new ErrorEscenario(n, campo + " no es un entero: '" + v + "'");
        }
    }
}
