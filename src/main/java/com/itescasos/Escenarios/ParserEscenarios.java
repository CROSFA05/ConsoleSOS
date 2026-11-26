package com.itescasos.Escenarios;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.itescasos.IO.ErrorEscenario;
import com.itescasos.IO.EscenarioIO;

/**
 * Lectura de escenarios .txt con secciones
 * [procesos] [planificacion] [memoria] [io] [archivos] [seguridad].
 *
 * Convenciones documentadas en el README:
 * <ul>
 *   <li>El tiempo es entero; cada ráfaga se mide en ticks.</li>
 *   <li>{@code cpu=2+3, io=0+2} significa CPU 2, E/S 2, CPU 3. Los elementos de
 *       {@code io} se alinean con los de {@code cpu}; si faltan se rellena con 0
 *       y, con una sola ráfaga de CPU, {@code io>0} es la E/S final.</li>
 *   <li>{@code referencias=0,1,2} pertenece al PID indicado por {@code pid}; las
 *       referencias explícitas {@code R1, pid=P1, pagina=0, t=2} permiten
 *       asociarlas a un PID y a un instante.</li>
 *   <li>La sección [io] se delega al parser del módulo 4; {@code algoritmo=scan}
 *       es alias de {@code politica=scan}.</li>
 *   <li>{@code usuario=docente} selecciona la identidad a AUTENTICAR, nunca la
 *       impone: la clave se solicita por entorno/entrada estándar.</li>
 * </ul>
 * Cualquier error se reporta con el número de línea del archivo.
 */
public final class ParserEscenarios {

    private static final Set<String> SECCIONES = new HashSet<String>(Arrays.asList(
            "procesos", "planificacion", "memoria", "io", "archivos", "seguridad"));

    private ParserEscenarios() { }

    public static Escenario leer(Path archivo) throws IOException {
        return analizar(Files.readAllLines(archivo, StandardCharsets.UTF_8), archivo);
    }

    public static Escenario analizar(List<String> entradas, Path origen) {
        Escenario e = new Escenario();
        e.origen = origen;
        String seccion = null;
        // Se conserva la numeración original para que EscenarioIO informe líneas reales.
        List<String> lineasNormalizadas = new ArrayList<String>();
        int n = 0;
        for (String crudaOriginal : entradas) {
            n++;
            String cruda = n == 1 ? quitarBom(crudaOriginal) : crudaOriginal;
            lineasNormalizadas.add(cruda);
            String sinComentario = sinComentario(cruda);
            String l = sinComentario.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("[")) {
                if (!l.endsWith("]") || l.length() <= 2) {
                    throw new ErrorEscenario(n, "sección mal formada: " + l);
                }
                seccion = l.substring(1, l.length() - 1).trim().toLowerCase(Locale.ROOT);
                if (!SECCIONES.contains(seccion)) {
                    throw new ErrorEscenario(n, "sección desconocida: " + seccion
                            + " (procesos, planificacion, memoria, io, archivos, seguridad)");
                }
                if (seccion.equals("io")) {
                    e.tieneIo = true;
                }
                continue;
            }
            if (seccion == null) {
                throw new ErrorEscenario(n, "línea fuera de cualquier sección: " + l);
            }
            if (seccion.equals("io")) {
                continue;
            }
            if (seccion.equals("procesos")) {
                proceso(e, l, n);
            } else if (seccion.equals("planificacion")) {
                planificacion(e, l, n);
            } else if (seccion.equals("memoria")) {
                memoria(e, l, n);
            } else if (seccion.equals("archivos")) {
                archivos(e, l, n);
            } else if (seccion.equals("seguridad")) {
                seguridad(e, l, n);
            }
        }

        if (e.tieneIo) {
            List<String> normalizadas = new ArrayList<String>();
            for (String lio : lineasNormalizadas) {
                String reemplazo = lio.trim().toLowerCase(Locale.ROOT).startsWith("algoritmo")
                        ? lio.replaceFirst("(?i)^\\s*algoritmo\\s*=", "politica =") : lio;
                normalizadas.add(reemplazo);
            }
            e.io = EscenarioIO.analizar(normalizadas);
        }
        validar(e);
        return e;
    }

    // ------------------------------------------------------------------ secciones

    private static void proceso(Escenario e, String l, int n) {
        List<String> campos = dividir(l, n);
        String pid = campos.get(0).trim();
        if (pid.isEmpty() || pid.indexOf('=') >= 0) {
            throw new ErrorEscenario(n, "se esperaba un PID antes de la coma: " + l);
        }
        for (Escenario.ProcDef p : e.procesos) {
            if (p.pid.equals(pid)) {
                throw new ErrorEscenario(n, "PID duplicado: " + pid);
            }
        }
        java.util.Map<String, String> kv = claveValor(campos, n);
        int llegada = entero(opcion(kv, "llegada", "t"), n, "llegada", 0);
        List<Integer> cpu = rafagas(opcion(kv, "cpu", null), n, "cpu", true);
        String ioTexto = opcion(kv, "io", null);
        List<Integer> io = ioTexto == null ? new ArrayList<Integer>() : rafagas(ioTexto, n, "io", false);
        if (io.isEmpty()) {
            for (int i = 0; i < cpu.size(); i++) io.add(0);
        } else if (io.size() == 1 && cpu.size() > 1) {
            int primera = io.get(0);
            io = new ArrayList<Integer>();
            io.add(primera);
            for (int i = 1; i < cpu.size(); i++) io.add(0);
        } else {
            while (io.size() < cpu.size() - 1) io.add(0);
        }
        if (io.size() > cpu.size()) {
            throw new ErrorEscenario(n, "la lista io tiene más elementos que cpu");
        }
        int prioridad = entero(opcion(kv, "prioridad", "0"), n, "prioridad", Integer.MIN_VALUE);
        int memoria = entero(opcion(kv, "memoria", "0"), n, "memoria", 0);
        Integer paginas = null;
        if (kv.containsKey("paginas")) {
            paginas = entero(kv.get("paginas"), n, "paginas", 1);
        }
        String operacion = null;
        String ruta = null;
        String texto = null;
        if (kv.containsKey("leer")) {
            operacion = "leer";
            ruta = kv.get("leer");
        } else if (kv.containsKey("escribir")) {
            operacion = "escribir";
            String v = kv.get("escribir");
            int p = v.indexOf(':');
            if (p <= 0) throw new ErrorEscenario(n, "escribir debe ser ruta:texto");
            ruta = v.substring(0, p).trim();
            texto = sinComillas(v.substring(p + 1).trim());
        }
        soloClaves(kv, Arrays.asList("llegada", "t", "cpu", "io", "prioridad", "memoria",
                "paginas", "leer", "escribir"), n);
        e.procesos.add(new Escenario.ProcDef(pid, llegada, cpu, io, prioridad, memoria, paginas,
                operacion, ruta, texto, n));
    }

    private static void planificacion(Escenario e, String l, int n) {
        String[] kv = unaClave(l, n);
        String k = kv[0].toLowerCase(Locale.ROOT);
        String v = sinComillas(kv[1]);
        if (k.equals("algoritmo")) {
            e.algoritmo = v;
        } else if (k.equals("quantum")) {
            e.quantum = entero(v, n, "quantum", 1);
        } else if (k.equals("comparar")) {
            e.compararCPU.addAll(lista(v));
        } else {
            throw new ErrorEscenario(n, "clave desconocida en [planificacion]: " + k);
        }
    }

    private static void memoria(Escenario e, String l, int n) {
        int igual = l.indexOf('=');
        String posibleClave = igual > 0 ? l.substring(0, igual).trim().toLowerCase(Locale.ROOT) : "";
        Set<String> configuracion = new HashSet<String>(Arrays.asList("marcos_totales", "marcos",
                "tam_pagina", "tam_pagina_bytes", "reemplazo", "algoritmo", "carga_pagina_ticks",
                "pid", "referencias", "comparar"));
        if (configuracion.contains(posibleClave)) {
            configuracionMemoria(e, l, n);
            return;
        }
        List<String> campos = dividir(l, n);
        String primero = campos.get(0).trim();
        boolean esReferencia = campos.size() > 1
                && (primero.indexOf('=') >= 0 || primero.matches("(?i)R\\d+"));
        if (esReferencia) {
            String etiqueta = (primero.indexOf('=') >= 0 || primero.matches("(?i)R\\d+"))
                    ? primero : "R" + e.referencias.size();
            java.util.Map<String, String> kv = claveValor(campos, n);
            String pid = kv.get("pid");
            if (pid == null) {
                throw new ErrorEscenario(n, "la referencia necesita pid=");
            }
            int t = entero(opcion(kv, "t", "0"), n, "t", 0);
            int pagina = entero(opcion(kv, "pagina", opcion(kv, "pag", null)), n, "pagina", 0);
            String op = opcion(kv, "op", "leer").toLowerCase(Locale.ROOT);
            boolean escritura;
            if (op.equals("leer") || op.equals("r")) escritura = false;
            else if (op.equals("escribir") || op.equals("w")) escritura = true;
            else throw new ErrorEscenario(n, "op debe ser leer o escribir");
            for (Escenario.RefDef r : e.referencias) {
                if (r.etiqueta.equals(etiqueta)) {
                    throw new ErrorEscenario(n, "referencia duplicada: " + etiqueta);
                }
            }
            soloClaves(kv, Arrays.asList("pid", "t", "pagina", "pag", "op"), n);
            e.referencias.add(new Escenario.RefDef(etiqueta, pid, t, pagina, escritura, n));
            return;
        }
        throw new ErrorEscenario(n, "se esperaba una referencia o una clave=valor: " + l);
    }

    private static void configuracionMemoria(Escenario e, String l, int n) {
        String[] kv = unaClave(l, n);
        String k = kv[0].toLowerCase(Locale.ROOT);
        String v = sinComillas(kv[1]);
        if (k.equals("marcos_totales") || k.equals("marcos")) {
            e.marcosTotales = entero(v, n, k, 1);
        } else if (k.equals("tam_pagina") || k.equals("tam_pagina_bytes")) {
            e.tamPagina = entero(v, n, k, 1);
        } else if (k.equals("reemplazo") || k.equals("algoritmo")) {
            e.reemplazo = v;
        } else if (k.equals("carga_pagina_ticks")) {
            e.cargaPaginaTicks = entero(v, n, k, 1);
        } else if (k.equals("pid")) {
            e.pidReferencias = v;
        } else if (k.equals("referencias")) {
            if (!e.referencias.isEmpty()) {
                throw new ErrorEscenario(n, "no se pueden mezclar referencias= y líneas ref");
            }
            for (String trozo : lista(v)) {
                int pagina = entero(trozo, n, "referencia", 0);
                e.referencias.add(new Escenario.RefDef("R" + (e.referencias.size() + 1),
                        e.pidReferencias, 0, pagina, false, n));
            }
        } else if (k.equals("comparar")) {
            e.compararMemoria.addAll(lista(v));
        } else {
            throw new ErrorEscenario(n, "clave desconocida en [memoria]: " + k);
        }
    }

    private static void archivos(Escenario e, String l, int n) {
        List<String> tokens = tokens(l, n);
        String cmd = tokens.get(0).toLowerCase(Locale.ROOT);
        int inicioValor = tokens.size() > 1 && "=".equals(tokens.get(1)) ? 2 : 1;
        if (cmd.equals("imagen")) {
            exigir(tokens, inicioValor + 1, "imagen <archivo.img>", n);
            e.imagen = sinComillas(tokens.get(inicioValor));
            return;
        }
        if (cmd.equals("formatear")) {
            exigir(tokens, inicioValor + 1, "formatear si|no", n);
            String v = sinComillas(tokens.get(inicioValor)).toLowerCase(Locale.ROOT);
            if (!v.equals("si") && !v.equals("no") && !v.equals("yes")) {
                throw new ErrorEscenario(n, "formatear debe ser si o no");
            }
            e.formatear = v.equals("si") || v.equals("yes");
            return;
        }
        String[] permitidos = { "mkdir", "crear", "create", "escribir", "write", "leer", "read",
                "renombrar", "rename", "borrar", "rm", "cifrar", "descifrar", "adjuntar" };
        if (!Arrays.asList(permitidos).contains(cmd)) {
            throw new ErrorEscenario(n, "operación desconocida en [archivos]: " + cmd);
        }
        List<String> args = new ArrayList<String>();
        for (int i = 1; i < tokens.size(); i++) {
            args.add(sinComillas(tokens.get(i)));
        }
        if (cmd.equals("crear") || cmd.equals("create")) {
            exigir(args, 1, cmd + " <ruta> [tam]", n);
            if (args.size() > 1) {
                entero(args.get(1), n, "tam", 0);
            }
        } else if (cmd.equals("escribir") || cmd.equals("write") || cmd.equals("adjuntar")) {
            exigir(args, 2, cmd + " <ruta> \"texto\"", n);
        } else {
            exigir(args, cmd.equals("renombrar") || cmd.equals("rename") ? 2 : 1,
                    cmd + " <ruta> ...", n);
        }
        e.archivos.add(new Escenario.Accion(cmd, args, n));
    }

    private static void seguridad(Escenario e, String l, int n) {
        String[] kv = unaClave(l, n);
        String k = kv[0].toLowerCase(Locale.ROOT);
        String v = sinComillas(kv[1]);
        if (k.equals("usuario")) {
            e.usuario = v;
        } else if (k.equals("clave") || k.equals("clave_archivo")) {
            e.claveArchivo = v;
        } else if (k.equals("permiso") || k.equals("revocar")) {
            String[] partes = v.split(":", 3);
            if (partes.length != 3) {
                throw new ErrorEscenario(n, k + " debe ser usuario:objeto:rwx");
            }
            e.permisos.add(new Escenario.PermisoDef(partes[0].trim(), partes[1].trim(),
                    partes[2].trim(), k.equals("revocar"), n));
        } else if (k.equals("dispositivo")) {
            String[] partes = v.split(":", 3);
            if (partes.length != 3) {
                throw new ErrorEscenario(n, "dispositivo debe ser nombre:usuario:rwx");
            }
            e.dispositivos.add(new Escenario.DispositivoDef(partes[0].trim(),
                    partes[1].trim(), partes[2].trim(), n));
        } else if (k.equals("admin")) {
            e.administradores.add(v);
        } else {
            throw new ErrorEscenario(n, "clave desconocida en [seguridad]: " + k);
        }
    }

    // ------------------------------------------------------------------ validación final

    private static void validar(Escenario e) {
        if (e.tieneProcesos()) {
            Set<String> pids = new HashSet<String>();
            for (Escenario.ProcDef p : e.procesos) pids.add(p.pid);
            if (!pids.contains(e.pidReferencias) && !e.referencias.isEmpty()) {
                throw new ErrorEscenario(e.referencias.get(0).linea,
                        "el PID de referencias '" + e.pidReferencias + "' no está en [procesos]");
            }
            for (Escenario.RefDef r : e.referencias) {
                if (!pids.contains(r.pid)) {
                    throw new ErrorEscenario(r.linea, "PID de referencia desconocido: " + r.pid);
                }
            }
        }
        if (e.io != null) {
            for (EscenarioIO.Peticion p : e.io.peticiones) {
                if (e.tieneProcesos() && e.proceso(p.pid) == null && !"-".equals(p.pid)) {
                    throw new ErrorEscenario(p.linea, "PID de E/S desconocido: " + p.pid);
                }
            }
        }
        for (String a : e.compararCPU) validarAlgoritmo(a, 0, "CPU");
        validarAlgoritmo(e.algoritmo, 0, "CPU");
        for (String a : e.compararMemoria) validarAlgoritmo(a, 0, "memoria");
        validarAlgoritmo(e.reemplazo, 0, "memoria");
    }

    private static void validarAlgoritmo(String texto, int linea, String donde) {
        String a = texto.trim().toLowerCase(Locale.ROOT).replace("ó", "o");
        Set<String> validos = new HashSet<String>(Arrays.asList(
                "fcfs", "sjf", "rr", "round robin", "round-robin", "prioridades",
                "fifo", "lru", "optimo", "reloj"));
        if (!validos.contains(a)) {
            throw new ErrorEscenario(linea, "algoritmo de " + donde + " desconocido: " + texto);
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static String quitarBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\ufeff' ? s.substring(1) : s;
    }

    private static String sinComentario(String l) {
        boolean comillas = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') comillas = !comillas;
            else if (c == '#' && !comillas) return l.substring(0, i);
        }
        return l;
    }

    /** Divide por comas fuera de comillas. */
    private static List<String> dividir(String l, int n) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') { comillas = !comillas; sb.append(c); }
            else if (c == ',' && !comillas) { out.add(sb.toString().trim()); sb.setLength(0); }
            else sb.append(c);
        }
        if (comillas) throw new ErrorEscenario(n, "comillas sin cerrar");
        out.add(sb.toString().trim());
        return out;
    }

    /** Divide en palabras respetando comillas dobles. */
    private static List<String> tokens(String l, int n) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean comillas = false;
        char comilla = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if ((c == '"' || c == '\'') && (!comillas || c == comilla)) {
                if (comillas) { comillas = false; comilla = 0; }
                else { comillas = true; comilla = c; }
                sb.append(c);
            } else if (Character.isWhitespace(c) && !comillas) {
                if (sb.length() > 0) { out.add(sb.toString()); sb.setLength(0); }
            } else {
                sb.append(c);
            }
        }
        if (comillas) throw new ErrorEscenario(n, "comillas sin cerrar");
        if (sb.length() > 0) out.add(sb.toString());
        if (out.isEmpty()) throw new ErrorEscenario(n, "línea vacía");
        return out;
    }

    private static java.util.Map<String, String> claveValor(List<String> campos, int n) {
        java.util.Map<String, String> kv = new java.util.LinkedHashMap<String, String>();
        for (int i = 1; i < campos.size(); i++) {
            String c = campos.get(i);
            int p = c.indexOf('=');
            if (p < 0) throw new ErrorEscenario(n, "se esperaba clave=valor: " + c);
            String k = c.substring(0, p).trim().toLowerCase(Locale.ROOT);
            if (kv.put(k, c.substring(p + 1).trim()) != null) {
                throw new ErrorEscenario(n, "campo repetido: " + k);
            }
        }
        return kv;
    }

    private static String[] unaClave(String l, int n) {
        int p = l.indexOf('=');
        if (p <= 0) throw new ErrorEscenario(n, "se esperaba clave=valor: " + l);
        return new String[] { l.substring(0, p).trim(), l.substring(p + 1).trim() };
    }

    private static void soloClaves(java.util.Map<String, String> kv, List<String> permitidas, int n) {
        for (String k : kv.keySet()) {
            if (!permitidas.contains(k)) throw new ErrorEscenario(n, "campo desconocido: " + k);
        }
    }

    private static String opcion(java.util.Map<String, String> kv, String clave, String defecto) {
        String v = kv.get(clave);
        return v == null ? defecto : v;
    }

    private static int entero(String v, int n, String campo, int minimo) {
        if (v == null) throw new ErrorEscenario(n, "falta " + campo);
        try {
            int x = Integer.parseInt(sinComillas(v).trim());
            if (x < minimo) throw new ErrorEscenario(n, campo + " debe ser >= " + minimo + ": " + x);
            return x;
        } catch (NumberFormatException ex) {
            throw new ErrorEscenario(n, campo + " no es un entero: '" + v + "'");
        }
    }

    private static List<Integer> rafagas(String v, int n, String campo, boolean positivas) {
        List<Integer> out = new ArrayList<Integer>();
        if (v == null) return out;
        String texto = sinComillas(v);
        for (String trozo : texto.split("[+;]")) {
            out.add(entero(trozo, n, campo, positivas ? 1 : 0));
        }
        return out;
    }

    private static List<String> lista(String v) {
        List<String> out = new ArrayList<String>();
        for (String s : sinComillas(v).split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static String sinComillas(String s) {
        String v = s == null ? "" : s.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static void exigir(List<String> tokens, int min, String uso, int n) {
        if (tokens.size() < min) throw new ErrorEscenario(n, "Uso: " + uso);
    }
}
