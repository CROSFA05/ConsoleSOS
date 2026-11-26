package com.itescasos.Escenarios;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.itescasos.Archivos.ErrorFS;
import com.itescasos.Archivos.ImagenDiscoAcceso;
import com.itescasos.Archivos.InfoArchivo;
import com.itescasos.Archivos.SistemaArchivos;
import com.itescasos.Core.PCB;
import com.itescasos.Core.PlanificadorCPU;
import com.itescasos.Core.PlanificadorCPU.Gancho;
import com.itescasos.Core.PlanificadorCPU.Resultado;
import com.itescasos.Core.Reloj;
import com.itescasos.Core.RegistroEventos;
import com.itescasos.IO.AdministradorES;
import com.itescasos.IO.DiscoSimulado;
import com.itescasos.IO.EscenarioIO;
import com.itescasos.IO.ImagenDisco;
import com.itescasos.IO.PoliticaDisco;
import com.itescasos.IO.SimuladorES;
import com.itescasos.Memoria.GestorMemoria;
import com.itescasos.Seguridad.Derecho;
import com.itescasos.Seguridad.ServicioSeguridad;

/**
 * Ejecuta un Escenario usando los MISMOS servicios que la CLI: un solo reloj,
 * un solo PCB compartido, memoria con referencias por PID, E/S con drivers y
 * colas, archivos sobre el driver de disco y seguridad como punto de control.
 */
public class RunnerEscenarios {

    private static final int TAM_BLOQUE_IMAGEN = 128;
    private static final int TOTAL_BLOQUES_IMAGEN = 256;
    private static final int TOTAL_INODOS = 64;

    private final Escenario esc;
    private final ServicioSeguridad seguridad;
    private final Consumer<String> salida;
    private final Function<String, String> proveedorSecreto;
    private final Reloj reloj = new Reloj();
    private final RegistroEventos registro;

    private DiscoSimulado disco;
    private SistemaArchivos fs;
    private AdministradorES es;
    private boolean autenticadoPorEscenario;
    private int accesosBloqueFs;
    private final Set<String> pidsIntegrados = new java.util.HashSet<String>();

    public RunnerEscenarios(Escenario esc, ServicioSeguridad seguridad, Consumer<String> salida,
                            Function<String, String> proveedorSecreto) {
        this.esc = esc;
        this.seguridad = seguridad;
        this.salida = salida;
        this.proveedorSecreto = proveedorSecreto;
        this.registro = new RegistroEventos(reloj, salida);
        for (Escenario.ProcDef p : esc.procesos) {
            pidsIntegrados.add(p.pid);
        }
    }

    public RegistroEventos registro() { return registro; }

    // ==================================================================== modos

    public void planificacion() {
        cabecera("PLANIFICACIÓN DE CPU");
        List<PCB> carga = construirProcesos();
        List<String> algoritmos = esc.compararCPU.isEmpty()
                ? Collections.singletonList(esc.algoritmo) : esc.compararCPU;
        registrarConfiguracion("algoritmos=" + algoritmos + " quantum=" + esc.quantum);
        for (String algoritmo : algoritmos) {
            PlanificadorCPU motor = new PlanificadorCPU();
            Resultado r = motor.ejecutar(carga, algoritmo, esc.quantum);
            salida.accept(r.toString());
        }
    }

    public void memoria() {
        cabecera("ADMINISTRADOR DE MEMORIA");
        if (esc.referencias.isEmpty()) {
            throw new IllegalStateException("el escenario no define referencias de memoria");
        }
        registrarConfiguracion("marcos=" + esc.marcosTotales + " tamPagina=" + esc.tamPagina
                + " pid=" + esc.pidReferencias);
        List<String> politicas = esc.compararMemoria.isEmpty()
                ? Collections.singletonList(esc.reemplazo) : esc.compararMemoria;
        for (String politica : politicas) {
            GestorMemoria gm = nuevaMemoria();
            List<Integer> cadena = new ArrayList<Integer>();
            for (Escenario.RefDef r : esc.referencias) {
                if (r.pid.equals(esc.pidReferencias)) cadena.add(r.pagina);
            }
            GestorMemoria.Resultado r = gm.simular(esc.pidReferencias, cadena, politica);
            salida.accept("--- Reemplazo " + politica + " ---");
            salida.accept(r.tabla());
            salida.accept(r.resumen());
        }
    }

    public void io() {
        cabecera("ENTRADA/SALIDA (comparación de políticas)");
        if (esc.io == null) {
            throw new IllegalStateException("el escenario no tiene sección [io]");
        }
        List<SimuladorES.Corrida> corridas = SimuladorES.ejecutar(esc.io);
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        SimuladorES.imprimir(esc.io, corridas,
                new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8));
        salida.accept(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Ciclo completo: archivos + permisos + cifrado + memoria + CPU + E/S en un solo reloj. */
    public void completo() {
        cabecera("CICLO INTEGRADO");
        try {
            autenticarDesdeEscenario();
            abrirVolumen();
            ejecutarArchivos();
            aplicarSeguridad();
            iniciarSubsistemaIo();
            ejecutarCargaIntegrada();
            salida.accept("--- Resumen de E/S ---");
            salida.accept(es.resumen());
            salida.accept("Accesos a bloques del FS por el driver: " + accesosBloqueFs
                    + " (detalle agregado; cada bloque pasó por la cola del disco)");
            salida.accept("--- Salida de terminal ---");
            salida.accept(es.driver(DiscoSimulado.TERMINAL) instanceof com.itescasos.IO.DriverTerminal
                    ? ((com.itescasos.IO.DriverTerminal) es.driver(DiscoSimulado.TERMINAL)).salida().toString()
                    : "(sin salida)");
        } finally {
            cerrar();
        }
    }

    /** Ejecuta todas las secciones presentes, en el orden de la demostración. */
    public void ejecutarTodo() {
        if (esc.tieneProcesos()) planificacion();
        if (!esc.referencias.isEmpty()) memoria();
        if (esc.tieneIo) io();
        if (esc.tieneProcesos() || !esc.archivos.isEmpty()) completo();
    }

    // ==================================================================== integración

    private void autenticarDesdeEscenario() {
        if (esc.usuario == null || esc.usuario.trim().isEmpty()) {
            registro.emitir("SEC", "-", "sesión", "AUTH", "NO_DECLARADA");
            return;
        }
        String clave = proveedorSecreto == null ? null : proveedorSecreto.apply("login:" + esc.usuario);
        if (clave == null) clave = System.getenv("SOS_CLAVE");
        if (clave == null) {
            throw new IllegalStateException("se requiere la clave de " + esc.usuario
                    + " (variable SOS_CLAVE o entrada estándar)");
        }
        if (!seguridad.login(esc.usuario, clave)) {
            throw new IllegalStateException("credenciales incorrectas para " + esc.usuario);
        }
        autenticadoPorEscenario = true;
    }

    private void abrirVolumen() {
        Path imagen = Paths.get(esc.imagen);
        try {
            boolean nueva = esc.formatear || !Files.exists(imagen);
            // Una imagen recién creada todavía no tiene superbloque: se usa la geometría
            // declarada. Solo se lee del encabezado cuando la imagen ya está formateada.
            int tamBloque = TAM_BLOQUE_IMAGEN;
            if (nueva) {
                if (!esc.formatear) {
                    registro.emitir("FS", "sistema", esc.imagen, "IMAGEN_NUEVA", "FORMATEO_INICIAL");
                }
                ImagenDisco crear = ImagenDisco.crear(imagen, TAM_BLOQUE_IMAGEN,
                        TOTAL_BLOQUES_IMAGEN, true);
                crear.cerrar();
            } else {
                tamBloque = ImagenDiscoAcceso.tamBloqueDe(imagen);
            }
            int totalBloques = (int) (Files.size(imagen) / tamBloque);
            disco = new DiscoSimulado(ImagenDisco.abrir(imagen, tamBloque), reloj,
                    politicaIntegrada(), 1, 10);
            es = disco.administrador();
            accesosBloqueFs = 0;
            // Se muestran las solicitudes de los procesos del escenario; las del FS se
            // cuentan y se resumen al final para que la demo no produzca cientos de líneas.
            es.ponerLog(new Consumer<String>() {
                @Override public void accept(String linea) {
                    if (pidsIntegrados.contains(pidDeLinea(linea))) {
                        registro.texto(linea);
                    } else if (linea.contains("evento=IO_FIN")) {
                        accesosBloqueFs++;
                    }
                }
            });
            if (esc.formatear) {
                SistemaArchivos.formatear(disco.bloques(), TOTAL_INODOS);
                seguridad.bootstrap();
                registro.emitir("FS", seguridad.usuario(), esc.imagen, "FORMATO",
                        "bloques=" + totalBloques + " inodos=" + TOTAL_INODOS);
            }
            disco.bloques().ponerPid(seguridad.usuario() == null ? "FS" : seguridad.usuario());
            fs = SistemaArchivos.montar(disco.bloques(), seguridad.control());
            registro.emitir("FS", seguridad.usuario(), esc.imagen, "MONTAJE",
                    fs.fueDesmontadoLimpio() ? "LIMPIO" : "NO_LIMPIO");
            registrarRutas("/");
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo preparar la imagen " + imagen + ": " + e.getMessage());
        }
    }

    private PoliticaDisco politicaIntegrada() {
        // El volumen montado usa FCFS para que el montaje no dependa de la política
        // que se esté comparando. SCAN y C-SCAN se demuestran en la sección [io].
        return PoliticaDisco.FCFS;
    }

    /** Extrae el PID de una línea de log de E/S; "" si no lo tiene. */
    private static String pidDeLinea(String linea) {
        int i = linea.indexOf("pid=");
        if (i < 0) return "";
        int inicio = i + 4;
        int fin = inicio;
        while (fin < linea.length() && !Character.isWhitespace(linea.charAt(fin))) fin++;
        return linea.substring(inicio, fin);
    }

    private void registrarRutas(String ruta) {
        try {
            for (InfoArchivo info : fs.listar(ruta)) {
                seguridad.control().objetoCargado(info.idObjeto, info.ruta);
                if (info.tipo == com.itescasos.Archivos.TipoObjeto.DIRECTORIO) {
                    registrarRutas(info.ruta);
                }
            }
        } catch (ErrorFS e) {
            registro.emitir("FS", seguridad.usuario(), ruta, "RUTAS", "DENEGADO: " + e.codigo());
        }
    }

    private void ejecutarArchivos() {
        for (Escenario.Accion accion : esc.archivos) {
            String cmd = accion.comando.toLowerCase(Locale.ROOT);
            List<String> a = accion.argumentos;
            String ruta = a.isEmpty() ? "-" : a.get(0);
            try {
                if (cmd.equals("mkdir")) {
                    fs.mkdir(ruta);
                } else if (cmd.equals("crear") || cmd.equals("create")) {
                    int tam = a.size() > 1 ? Integer.parseInt(a.get(1)) : 0;
                    fs.crearArchivo(ruta, tam);
                } else if (cmd.equals("escribir") || cmd.equals("write")) {
                    fs.reemplazarContenido(ruta, a.get(1).getBytes(StandardCharsets.UTF_8));
                } else if (cmd.equals("adjuntar")) {
                    fs.agregar(ruta, a.get(1).getBytes(StandardCharsets.UTF_8));
                } else if (cmd.equals("leer") || cmd.equals("read")) {
                    InfoArchivo info = fs.info(ruta);
                    byte[] datos = fs.leerTodo(ruta);
                    salida.accept("lectura " + ruta + " (" + datos.length + " bytes"
                            + (info.cifrado() ? ", cifrado — sin texto claro" : "") + ")");
                    seguridad.registrarLectura(ruta, info.cifrado());
                } else if (cmd.equals("renombrar") || cmd.equals("rename")) {
                    int id = fs.info(ruta).idObjeto;
                    fs.renombrar(ruta, a.get(1));
                    seguridad.control().objetoRenombrado(id, a.get(1));
                    seguridad.guardar();
                } else if (cmd.equals("borrar") || cmd.equals("rm")) {
                    fs.eliminar(ruta);
                } else if (cmd.equals("cifrar")) {
                    seguridad.cifrar(fs, ruta, claveArchivo(accion));
                } else if (cmd.equals("descifrar")) {
                    boolean ok = seguridad.descifrar(fs, ruta, claveArchivo(accion));
                    salida.accept("descifrado " + ruta + ": " + (ok ? "clave correcta" : "sin cambios"));
                }
                registro.emitir("FS", seguridad.usuario(), ruta, cmd.toUpperCase(Locale.ROOT), "OK");
            } catch (ErrorFS e) {
                // Una denegación es parte de la evidencia: se registra y no altera el archivo.
                registro.emitir("FS", seguridad.usuario(), ruta, cmd.toUpperCase(Locale.ROOT),
                        "DENEGADO " + e.codigo());
            }
        }
        seguridad.guardar();
    }

    /**
     * La clave de cifrado NUNCA se toma de una acción del escenario ni se escribe en el log.
     * {@code clave_archivo=NOMBRE} declara qué variable de entorno debe consultarse; si no
     * se declara se usa {@code SOS_CLAVE_ARCHIVO} (o el proveedor interactivo de la CLI).
     */
    private String claveArchivo(Escenario.Accion accion) {
        String variable = esc.claveArchivo == null || esc.claveArchivo.trim().isEmpty()
                ? "SOS_CLAVE_ARCHIVO" : esc.claveArchivo.trim();
        String clave = System.getenv(variable);
        if (clave == null && proveedorSecreto != null) {
            clave = proveedorSecreto.apply(variable);
        }
        if (clave == null) {
            throw new IllegalStateException("se requiere la clave de cifrado en " + variable);
        }
        return clave;
    }

    private void aplicarSeguridad() {
        for (Escenario.PermisoDef p : esc.permisos) {
            try {
                int id = resolverObjeto(p.objeto);
                if (p.revocar) seguridad.revocar(p.usuario, id);
                else seguridad.conceder(p.usuario, id, p.rwx);
            } catch (ErrorFS e) {
                registro.emitir("SEC", seguridad.usuario(), p.objeto, "PERMISO",
                        "NO_APLICADO " + e.codigo());
            }
        }
        for (Escenario.DispositivoDef d : esc.dispositivos) {
            seguridad.concederDispositivo(d.dispositivo, d.usuario, d.rwx);
        }
        for (String admin : esc.administradores) {
            seguridad.hacerAdministrador(admin);
        }
    }

    private int resolverObjeto(String objeto) {
        String s = objeto.trim();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        InfoArchivo info = fs.info(s);
        return info.idObjeto;
    }

    private void iniciarSubsistemaIo() {
        // El administrador ya tiene los drivers creados al abrir la imagen.
        if (es.driver(DiscoSimulado.TERMINAL) instanceof com.itescasos.IO.DriverTerminal) {
            com.itescasos.IO.DriverTerminal t =
                    (com.itescasos.IO.DriverTerminal) es.driver(DiscoSimulado.TERMINAL);
            for (String linea : esc.io == null ? Collections.<String>emptyList() : esc.io.entradaTerminal) {
                t.ponerEntrada(linea);
            }
        }
    }

    private void ejecutarCargaIntegrada() {
        if (!esc.tieneProcesos()) {
            registro.texto("(sin procesos en el escenario; se omitió CPU/memoria integrados)");
            return;
        }
        final List<PCB> carga = construirProcesos();
        final GestorMemoria memoria = nuevaMemoria();
        for (Escenario.ProcDef p : esc.procesos) {
            memoria.asignarEspacio(p.pid, paginasDe(p));
        }
        // Referencias por PID, en el orden declarado; se entregan al primer despacho >= t.
        final Map<String, Queue<Escenario.RefDef>> pendientes =
                new LinkedHashMap<String, Queue<Escenario.RefDef>>();
        for (Escenario.ProcDef p : esc.procesos) {
            pendientes.put(p.pid, new ArrayDeque<Escenario.RefDef>());
        }
        final List<Escenario.RefDef> referencias = new ArrayList<Escenario.RefDef>(esc.referencias);
        Collections.sort(referencias, new Comparator<Escenario.RefDef>() {
            @Override public int compare(Escenario.RefDef a, Escenario.RefDef b) {
                int c = Integer.compare(a.t, b.t);
                return c != 0 ? c : Integer.compare(a.linea, b.linea);
            }
        });
        final List<EscenarioIO.Peticion> peticionesIo = esc.io == null
                ? Collections.<EscenarioIO.Peticion>emptyList()
                : new ArrayList<EscenarioIO.Peticion>(esc.io.peticiones);
        final boolean[] enviadas = new boolean[peticionesIo.size()];
        final boolean[] entregadas = new boolean[referencias.size()];
        final boolean[] archivoHecho = new boolean[esc.procesos.size()];
        final Map<String, Integer> indicePorPid = new HashMap<String, Integer>();
        for (int i = 0; i < esc.procesos.size(); i++) {
            indicePorPid.put(esc.procesos.get(i).pid, i);
        }

        Gancho gancho = new Gancho() {
            @Override public void alTick(int t) {
                for (int i = 0; i < peticionesIo.size(); i++) {
                    if (enviadas[i] || peticionesIo.get(i).t > t) continue;
                    EscenarioIO.Peticion p = peticionesIo.get(i);
                    enviadas[i] = true;
                    if (seguridad.haySesion() && !seguridad.autorizarDispositivo(
                            p.dispositivo, Derecho.EJECUTAR, p.pid)) {
                        registro.emitir("IO", p.pid, p.dispositivo, "SOLICITUD", "DENEGADO");
                        continue;
                    }
                    es.enviar(p.pid, p.dispositivo, p.op, p.destino, p.tamano,
                            p.datos, p.modo);
                }
                for (int i = 0; i < referencias.size(); i++) {
                    Escenario.RefDef r = referencias.get(i);
                    if (entregadas[i] || r.t > t) continue;
                    Queue<Escenario.RefDef> q = pendientes.get(r.pid);
                    if (q == null) {
                        throw new IllegalStateException("referencia para PID desconocido: " + r.pid);
                    }
                    q.add(r);
                    entregadas[i] = true;
                }
            }

            @Override public int alDespachar(PCB proceso, int t) {
                int bloqueo = 0;
                Queue<Escenario.RefDef> q = pendientes.get(proceso.pid);
                Escenario.RefDef r = q == null ? null : q.poll();
                if (r != null) {
                    Escenario.ProcDef def = esc.proceso(proceso.pid);
                    int paginas = def == null ? 1 : paginasDe(def);
                    if (r.pagina >= paginas) {
                        throw new IllegalStateException("referencia inválida " + proceso.pid
                                + " página=" + r.pagina + " (espacio=" + paginas + ")");
                    }
                    boolean fallo = memoria.referenciar(proceso.pid, r.pagina, r.escritura);
                    registro.emitir("MEMORIA", proceso.pid, "pagina=" + r.pagina,
                            fallo ? "FALLO" : "ACIERTO", "marcos=" + memoria.estadoMarcos());
                    if (fallo) {
                        bloqueo += esc.cargaPaginaTicks;
                    }
                }
                Integer indice = indicePorPid.get(proceso.pid);
                if (indice != null && !archivoHecho[indice]) {
                    Escenario.ProcDef def = esc.procesos.get(indice);
                    if (def.operacionArchivo != null) {
                        archivoHecho[indice] = true;
                        // La lectura/escritura pasa por el FS y por el driver de disco:
                        // el tiempo consumido es espera de E/S, no ejecución de CPU.
                        int antes = reloj.ahora();
                        operacionArchivoProceso(def);
                        bloqueo += Math.max(0, reloj.ahora() - antes);
                    }
                }
                return bloqueo;
            }

            @Override public void alTerminar(PCB proceso, int t) {
                memoria.liberar(proceso.pid);
                es.cancelarDePid(proceso.pid);
                registro.emitir("MEMORIA", proceso.pid, "-", "LIBERACION",
                        "marcos=" + memoria.estadoMarcos());
            }
        };

        PlanificadorCPU motor = new PlanificadorCPU();
        Resultado r = motor.ejecutarConGancho(carga, esc.algoritmo, esc.quantum, reloj, es, registro, gancho);
        salida.accept(r.toString());
        salida.accept("--- Memoria final ---");
        salida.accept("política=" + memoria.politica() + " fallos=" + memoria.fallos()
                + " aciertos=" + memoria.aciertos() + " marcos=" + memoria.estadoMarcos());
    }

    /** Operación de archivo pedida por un proceso: pasa por el FS y por la matriz de acceso. */
    private void operacionArchivoProceso(Escenario.ProcDef p) {
        String evento = p.operacionArchivo.toUpperCase(Locale.ROOT) + "_PROCESO";
        try {
            if (p.operacionArchivo.equals("leer")) {
                InfoArchivo info = fs.info(p.rutaArchivo);
                byte[] datos = fs.leerTodo(p.rutaArchivo);
                registro.emitir("FS", p.pid, p.rutaArchivo, evento,
                        "OK bytes=" + datos.length + (info.cifrado() ? " cifrado=si" : ""));
                seguridad.registrarLectura(p.rutaArchivo, info.cifrado());
            } else {
                byte[] datos = (p.textoArchivo == null ? "" : p.textoArchivo)
                        .getBytes(StandardCharsets.UTF_8);
                fs.reemplazarContenido(p.rutaArchivo, datos);
                registro.emitir("FS", p.pid, p.rutaArchivo, evento, "OK bytes=" + datos.length);
            }
        } catch (ErrorFS e) {
            registro.emitir("FS", p.pid, p.rutaArchivo, evento, "DENEGADO " + e.codigo());
        }
    }

    private int paginasDe(Escenario.ProcDef p) {
        if (p.paginas != null) return p.paginas;
        int porTamano = (p.memoriaBytes + esc.tamPagina - 1) / esc.tamPagina;
        int maxRef = 0;
        for (Escenario.RefDef r : esc.referencias) {
            if (r.pid.equals(p.pid)) maxRef = Math.max(maxRef, r.pagina + 1);
        }
        return Math.max(1, Math.max(porTamano, maxRef));
    }

    private GestorMemoria nuevaMemoria() {
        return new GestorMemoria(esc.marcosTotales, esc.tamPagina);
    }

    private List<PCB> construirProcesos() {
        List<PCB> out = new ArrayList<PCB>();
        for (Escenario.ProcDef p : esc.procesos) {
            out.add(new PCB(p.pid, p.llegada, p.cpu, p.io, p.prioridad, p.memoriaBytes));
        }
        return out;
    }

    // ==================================================================== apoyo

    private void cabecera(String titulo) {
        salida.accept("=== " + titulo + (esc.origen == null ? "" : " — " + esc.origen) + " ===");
    }

    private void registrarConfiguracion(String texto) {
        registro.texto("config modulo=" + texto);
    }

    private void cerrar() {
        if (fs != null) {
            try { fs.desmontar(); } catch (Exception ignored) { }
            fs = null;
        }
        if (disco != null) {
            disco.cerrar();
            disco = null;
        }
        if (autenticadoPorEscenario && seguridad.haySesion()) {
            seguridad.logout();
            autenticadoPorEscenario = false;
        }
        seguridad.guardar();
    }

    /** Proveedor de secretos: consola sin eco si está disponible; si no, entorno o stdin. */
    public static String pedirClave(String usuario, Function<String, String> alternativo) {
        if (alternativo != null) {
            String s = alternativo.apply(usuario);
            if (s != null) return s;
        }
        String entorno = System.getenv("SOS_CLAVE");
        if (entorno != null) return entorno;
        java.io.Console consola = System.console();
        if (consola != null) {
            char[] chars = consola.readPassword("Clave de " + usuario + ": ");
            return chars == null ? null : new String(chars);
        }
        try {
            salidaErr("Clave de " + usuario + ": ");
            BufferedReader lector = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            return lector.readLine();
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo leer la clave", e);
        }
    }

    private static void salidaErr(String s) {
        System.err.print(s);
        System.err.flush();
    }
}
