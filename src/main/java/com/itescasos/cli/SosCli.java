package com.itescasos.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.function.Function;

import com.itescasos.Archivos.ErrorFS;
import com.itescasos.Archivos.Fsck;
import com.itescasos.Archivos.HerramientasPrueba;
import com.itescasos.Archivos.ImagenDiscoAcceso;
import com.itescasos.Archivos.InfoArchivo;
import com.itescasos.Archivos.InfoEspacio;
import com.itescasos.Archivos.ReporteFsck;
import com.itescasos.Archivos.SistemaArchivos;
import com.itescasos.Archivos.TipoObjeto;
import com.itescasos.Core.PCB;
import com.itescasos.Core.PlanificadorCPU;
import com.itescasos.Core.Reloj;
import com.itescasos.Core.RegistroEventos;
import com.itescasos.Escenarios.Escenario;
import com.itescasos.Escenarios.ParserEscenarios;
import com.itescasos.Escenarios.RunnerEscenarios;
import com.itescasos.IO.DiscoSimulado;
import com.itescasos.IO.EscenarioIO;
import com.itescasos.IO.ImagenDisco;
import com.itescasos.IO.PoliticaDisco;
import com.itescasos.IO.SimuladorES;
import com.itescasos.Memoria.GestorMemoria;
import com.itescasos.Seguridad.Derecho;
import com.itescasos.Seguridad.ServicioSeguridad;

/**
 * Punto de entrada único del simulador SOS.
 *
 * <p>Un solo proceso Java crea un {@link Reloj}, un {@link ServicioSeguridad} y,
 * cuando hace falta, una pila {@link DiscoSimulado} para que el sistema de
 * archivos alcance el disco a través del driver. Cada invocación independiente
 * debe autenticarse de nuevo: la imagen y la matriz persisten, la sesión no.
 */
public class SosCli {

    private static final int TAM_BLOQUE = 128;
    private static final int TOTAL_BLOQUES = 256;
    private static final int TOTAL_INODOS = 64;

    private final Path usuariosPath;
    private final Path matrizPath;
    private final ServicioSeguridad seguridad;
    private final Reloj reloj = new Reloj();
    private final RegistroEventos registro;
    private final Scanner teclado;
    private final boolean interactivo;

    private Path imagen = Paths.get("disk.img");
    private DiscoSimulado disco;
    private SistemaArchivos fs;
    private int accesosBloque;
    private boolean corriendo = true;

    public static void main(String[] args) {
        try {
            SosCli cli = new SosCli(args.length == 0);
            cli.run(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private SosCli(boolean interactivo) throws IOException {
        this.interactivo = interactivo;
        this.teclado = interactivo ? new Scanner(System.in, "UTF-8") : null;
        String dir = System.getenv("SOS_SEGURIDAD_DIR");
        Path base = dir == null || dir.trim().isEmpty() ? Paths.get(".") : Paths.get(dir);
        this.usuariosPath = base.resolve("sos-usuarios.txt");
        this.matrizPath = base.resolve("sos-matriz.txt");
        this.seguridad = new ServicioSeguridad(usuariosPath, matrizPath, System.out::println);
        this.registro = new RegistroEventos(reloj, System.out::println);
        String img = System.getenv("SOS_IMAGEN");
        if (img != null && !img.trim().isEmpty()) {
            this.imagen = Paths.get(img.trim());
        }
    }

    private void run(String[] args) throws Exception {
        if (args.length == 0) {
            interactivo();
            return;
        }
        try {
            ejecutar(args);
        } finally {
            cerrarVolumen();
            seguridad.guardar();
        }
    }

    private void interactivo() {
        System.out.println("SOS — Simulador Educativo de Sistema Operativo");
        System.out.println("Escribe 'help' para ver los comandos; 'exit' para salir.\n");
        while (corriendo) {
            System.out.print(seguridad.haySesion() ? seguridad.usuario() + "@sos> " : "sos> ");
            System.out.flush();
            if (!teclado.hasNextLine()) break;
            String linea = teclado.nextLine().trim();
            if (linea.isEmpty()) continue;
            String[] partes = linea.split("\\s+");
            try {
                ejecutar(partes);
            } catch (ErrorFS e) {
                System.out.println("FS: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        if (seguridad.haySesion()) seguridad.logout();
        cerrarVolumen();
        seguridad.guardar();
        System.out.println("Sesión local invalidada. Hasta pronto.");
    }

    private void ejecutar(String[] args) throws Exception {
        if (args.length == 0) return;
        String cmd = args[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "help": case "?": ayuda(); break;
            case "exit": case "quit":
                corriendo = false;
                break;
            case "sim": sim(args); break;
            case "fs": fsCmd(args); break;
            case "fsck": verificar(args); break;
            case "auth": authCmd(args); break;
            case "sec": secCmd(args); break;
            default:
                throw new IllegalArgumentException("comando desconocido: " + cmd + " (escribe help)");
        }
    }

    private void ayuda() {
        System.out.println(
            "\n=== SOS CLI ===\n" +
            "  sim plan [escenario.txt]     Gantt y métricas de CPU (caso de control si omite el archivo)\n" +
            "  sim mem  [escenario.txt]     Fallos/aciertos de memoria (caso de control si lo omite)\n" +
            "  sim io   [escenario.txt]     FCFS/SCAN/C-SCAN (caso de control si lo omite)\n" +
            "  sim run  <escenario.txt>     Todas las secciones presentes + ciclo integrado\n\n" +
            "  fs format [imagen.img]       Crea y formatea una imagen persistente\n" +
            "  fs mount  [imagen.img]       Monta la imagen (sin sesión los permisos se deniegan)\n" +
            "  fs umount                    Cierra descriptores y marca desmontaje limpio\n" +
            "  fs mkdir <ruta> | fs create <ruta> [tam]\n" +
            "  fs write <ruta> <texto...> | fs append <ruta> <texto...>\n" +
            "  fs read <ruta> | fs ls [ruta] | fs stat <ruta> | fs df\n" +
            "  fs rename <origen> <destino> | fs rm <ruta>\n" +
            "  fs fsck [--fix] [--dano N]    Copia de prueba; no toca la imagen original\n" +
            "  fsck [img] [--fix] [--dano N] Igual que 'fs fsck' desde cualquier contexto\n" +
            "                                --dano N apaga el bit del bloque N solo en la copia\n\n" +
            "  auth login <usuario> [clave] | auth logout | auth who | auth users\n" +
            "  sec permiso <usuario> <id|ruta> <rwx> | sec revocar <usuario> <id|ruta>\n" +
            "  sec administrador <usuario> | sec dispositivo <nombre> <usuario> <rwx>\n" +
            "  sec matriz | sec usuarios\n" +
            "  sec cifrar <ruta> [clave] | sec descifrar <ruta> [clave]\n\n" +
            "  help | exit\n" +
            "SOS_IMAGEN=archivo.img fija la imagen por defecto y se monta automáticamente.\n" +
            "Sin argumentos entra en modo interactivo: se pueden encadenar comandos por stdin.\n" +
            "Secretos: SOS_CLAVE (login), SOS_CLAVE_ARCHIVO (cifrado) o entrada sin eco.\n");
    }

    // ==================================================================== SIM

    private void sim(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Uso: sim run|plan|mem|io [escenario.txt]");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("run")) {
            exigir(args, 3, "sim run <escenario.txt>");
            ejecutarEscenario(Paths.get(args[2]), "todo");
            return;
        }
        if (sub.equals("plan") || sub.equals("mem") || sub.equals("io")) {
            if (args.length >= 3) {
                ejecutarEscenario(Paths.get(args[2]), sub);
            } else if (sub.equals("plan")) {
                simPlan();
            } else if (sub.equals("mem")) {
                simMem();
            } else {
                simIo();
            }
            return;
        }
        throw new IllegalArgumentException("Subcomando sim desconocido: " + args[1]);
    }

    private void ejecutarEscenario(Path archivo, String modo) throws IOException {
        if (!Files.exists(archivo)) {
            throw new IllegalArgumentException("No existe el escenario: " + archivo);
        }
        Escenario esc = ParserEscenarios.leer(archivo);
        RunnerEscenarios runner = new RunnerEscenarios(esc, seguridad, System.out::println,
                this::proveerSecreto);
        if (modo.equals("plan")) {
            runner.planificacion();
        } else if (modo.equals("mem")) {
            runner.memoria();
        } else if (modo.equals("io")) {
            runner.io();
        } else {
            runner.ejecutarTodo();
        }
    }

    /** Caso de control del módulo 1: cinco procesos, sin E/S. */
    private void simPlan() {
        System.out.println("=== MÓDULO 1 — caso de control (5 procesos, cambio de contexto 0) ===");
        List<PCB> carga = Arrays.asList(
                new PCB("P1", 0, 5, 0, 3, 8192),
                new PCB("P2", 0, 3, 0, 1, 8192),
                new PCB("P3", 0, 1, 0, 4, 8192),
                new PCB("P4", 0, 2, 0, 2, 8192),
                new PCB("P5", 0, 4, 0, 2, 8192));
        PlanificadorCPU motor = new PlanificadorCPU();
        for (String algoritmo : Arrays.asList("FCFS", "SJF", "RR", "PRIORIDADES")) {
            System.out.println(motor.ejecutar(carga, algoritmo, 2));
        }
    }

    /** Caso de control del módulo 2: 6, 7 y 5 fallos con tres marcos. */
    private void simMem() {
        System.out.println("=== MÓDULO 2 — caso de control (0,1,2,0,3,1,4,0; 3 marcos) ===");
        List<Integer> cadena = Arrays.asList(0, 1, 2, 0, 3, 1, 4, 0);
        for (String politica : Arrays.asList("FIFO", "LRU", "OPTIMO")) {
            GestorMemoria memoria = new GestorMemoria(3, 4096);
            System.out.println("--- " + politica + " ---");
            System.out.println(memoria.simular("demo", cadena, politica).resumen());
        }
    }

    /** Caso de control del módulo 4: recorridos 210, 148 y 178 cilindros. */
    private void simIo() {
        List<String> lineas = Arrays.asList(
                "[io]",
                "politica = todas",
                "cilindros = 100",
                "bloquesPorCilindro = 1",
                "cabezal = 40",
                "direccion = asc",
                "R1, t=0, pid=P1, op=leer, destino=10",
                "R2, t=0, pid=P1, op=leer, destino=70",
                "R3, t=0, pid=P1, op=leer, destino=20",
                "R4, t=0, pid=P1, op=leer, destino=90",
                "terminal.entrada = hola");
        EscenarioIO esc = EscenarioIO.analizar(lineas);
        System.out.println("=== MÓDULO 4 — caso de control (cabezal 40; cola 10,70,20,90) ===");
        SimuladorES.imprimir(esc, SimuladorES.ejecutar(esc), System.out);
    }

    // ==================================================================== FS

    private void fsCmd(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Uso: fs <subcomando> ...");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "format": {
                Path p = args.length >= 3 ? Paths.get(args[2]) : imagen;
                formatear(p);
                break;
            }
            case "mount": {
                Path p = args.length >= 3 ? Paths.get(args[2]) : imagen;
                PoliticaDisco politica = args.length >= 4
                        ? politica(args[3]) : PoliticaDisco.FCFS;
                montar(p, politica);
                break;
            }
            case "umount":
                cerrarVolumen();
                System.out.println("Volumen desmontado limpiamente.");
                break;
            case "mkdir":
                exigirFs();
                exigir(args, 3, "fs mkdir <ruta>");
                fs.mkdir(args[2]);
                System.out.println("Directorio creado: " + args[2]);
                break;
            case "create":
                exigirFs();
                exigir(args, 3, "fs create <ruta> [tam]");
                int tam = args.length >= 4 ? Integer.parseInt(args[3]) : 0;
                fs.crearArchivo(args[2], tam);
                System.out.println("Archivo creado: " + args[2]);
                break;
            case "write": case "append": {
                exigirFs();
                exigir(args, 4, "fs " + sub + " <ruta> <texto...>");
                String texto = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                byte[] datos = texto.getBytes(StandardCharsets.UTF_8);
                if (sub.equals("write")) fs.reemplazarContenido(args[2], datos);
                else fs.agregar(args[2], datos);
                System.out.println((sub.equals("write") ? "Escrito" : "Añadido") + " en " + args[2]
                        + " (" + datos.length + " bytes)");
                break;
            }
            case "read": {
                exigirFs();
                exigir(args, 3, "fs read <ruta>");
                InfoArchivo info = fs.info(args[2]);
                byte[] datos = fs.leerTodo(args[2]);
                seguridad.registrarLectura(args[2], info.cifrado());
                if (info.cifrado()) {
                    System.out.println("[archivo cifrado: " + datos.length
                            + " bytes; sin texto claro. Use sec descifrar]");
                } else {
                    System.out.println(new String(datos, StandardCharsets.UTF_8));
                }
                break;
            }
            case "ls": {
                exigirFs();
                String ruta = args.length >= 3 ? args[2] : "/";
                for (InfoArchivo e : fs.listar(ruta)) {
                    String tipo = e.tipo == TipoObjeto.DIRECTORIO ? "d" : "-";
                    System.out.printf("%s%s id=%-4d %6d  %s%n", tipo,
                            e.cifrado() ? "C" : " ", e.idObjeto, e.tamano, e.ruta);
                }
                break;
            }
            case "rm":
                exigirFs();
                exigir(args, 3, "fs rm <ruta>");
                fs.eliminar(args[2]);
                System.out.println("Eliminado: " + args[2]);
                break;
            case "rename":
                exigirFs();
                exigir(args, 4, "fs rename <origen> <destino>");
                int id = fs.info(args[2]).idObjeto;
                fs.renombrar(args[2], args[3]);
                seguridad.control().objetoRenombrado(id, args[3]);
                seguridad.guardar();
                System.out.println("Renombrado: " + args[2] + " -> " + args[3]
                        + " (idObjeto=" + id + " sin cambios)");
                break;
            case "stat": {
                exigirFs();
                exigir(args, 3, "fs stat <ruta>");
                InfoArchivo st = fs.info(args[2]);
                System.out.printf("ruta=%s tipo=%s inodo=%d idObjeto=%d tam=%d cifrado=%s bloques=%s%n",
                        st.ruta, st.tipo, st.inodo, st.idObjeto, st.tamano, st.cifrado(), st.bloques);
                break;
            }
            case "df": {
                exigirFs();
                InfoEspacio esp = fs.espacio();
                System.out.print(esp.toString());
                break;
            }
            case "fsck":
                verificar(Arrays.copyOfRange(args, 1, args.length));
                break;
            default:
                throw new IllegalArgumentException("Subcomando fs desconocido: " + args[1]);
        }
    }

    private void formatear(Path destino) throws IOException {
        cerrarVolumen();
        ImagenDisco medio = ImagenDisco.crear(destino, TAM_BLOQUE, TOTAL_BLOQUES, true);
        try {
            SistemaArchivos.formatear(medio, TOTAL_INODOS);
        } finally {
            medio.cerrar();
        }
        imagen = destino;
        seguridad.bootstrap();
        registro.emitir("FS", "sistema", destino.toString(), "FORMATO",
                TAM_BLOQUE + "B/" + TOTAL_BLOQUES + " bloques, " + TOTAL_INODOS + " inodos");
        System.out.println("Imagen formateada: " + destino.toAbsolutePath()
                + ". Política inicial: admin/docente con rwx sobre / y /lost+found.");
    }

    private void montar(Path origen, PoliticaDisco politica) throws IOException {
        cerrarVolumen();
        int tamBloque = ImagenDiscoAcceso.tamBloqueDe(origen);
        disco = DiscoSimulado.abrirImagen(origen, tamBloque, reloj, politica, 1, 10);
        // Cada bloque pasa por el driver; se resume al desmontar para no llenar la
        // pantalla con cientos de líneas de E/S del propio sistema de archivos.
        accesosBloque = 0;
        disco.administrador().ponerLog(linea -> {
            if (linea.contains("evento=IO_FIN")) accesosBloque++;
        });
        fs = SistemaArchivos.montar(disco.bloques(), seguridad.control());
        imagen = origen;
        registro.emitir("FS", seguridad.usuario(), origen.toString(), "MONTAJE",
                fs.fueDesmontadoLimpio() ? "LIMPIO" : "NO_LIMPIO");
        System.out.println("Montado: " + origen.toAbsolutePath()
                + (fs.fueDesmontadoLimpio() ? " (limpio)" : " (no limpio — ejecuta fsck)"));
    }

    /** fsck se ejecuta SIEMPRE sobre una copia: la imagen original no se modifica. */
    private void verificar(String[] args) throws IOException {
        int desde = args[0].equalsIgnoreCase("fsck") ? 1 : 2;
        String rutaImg = args.length > desde && !args[desde].isEmpty() && !args[desde].startsWith("--")
                ? args[desde] : imagen.toString();
        boolean reparar = false;
        Integer dano = null;
        for (int i = desde; i < args.length; i++) {
            if ("--fix".equalsIgnoreCase(args[i])) {
                reparar = true;
            } else if ("--dano".equalsIgnoreCase(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Uso: fsck [img] [--fix] [--dano N]");
                }
                dano = Integer.parseInt(args[++i]);
            }
        }
        Path original = Paths.get(rutaImg);
        if (!Files.exists(original)) {
            throw new IllegalArgumentException("No existe la imagen: " + original);
        }
        cerrarVolumen();
        Path copia = Files.createTempFile("sos-fsck-", ".img");
        try {
            Files.copy(original, copia, StandardCopyOption.REPLACE_EXISTING);
            if (dano != null) {
                System.out.println(HerramientasPrueba.apagarBitDeBloque(original, copia, dano));
            }
            ImagenDisco medio = ImagenDisco.abrir(copia, ImagenDiscoAcceso.tamBloqueDe(original));
            try {
                ReporteFsck rep = Fsck.ejecutar(medio, reparar);
                System.out.println("Copia de prueba: " + copia.toAbsolutePath());
                System.out.println("Original sin cambios: " + original.toAbsolutePath());
                System.out.println(rep.texto());
            } finally {
                medio.cerrar();
            }
        } finally {
            Files.deleteIfExists(copia);
        }
    }

    /** Montaje automático bajo demanda: cada invocación es un proceso independiente. */
    private void exigirFs() throws Exception {
        if (fs == null) {
            if (!Files.exists(imagen)) {
                throw new IllegalStateException("No hay volumen montado. Use: fs format [img] o SOS_IMAGEN=img");
            }
            montar(imagen, PoliticaDisco.FCFS);
        }
    }

    private void cerrarVolumen() {
        if (fs != null) {
            try { fs.desmontar(); } catch (Exception ignored) { }
            fs = null;
        }
        if (disco != null) {
            disco.cerrar();
            disco = null;
            if (accesosBloque > 0) {
                System.out.println("Accesos a bloques por el driver de disco: " + accesosBloque);
            }
            accesosBloque = 0;
        }
    }

    // ==================================================================== AUTH / SEC

    private void authCmd(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Uso: auth login|logout|who|users");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("login")) {
            exigir(args, 3, "auth login <usuario> [clave]");
            String usuario = args[2];
            String clave = args.length >= 4 ? args[3] : proveerSecreto("login:" + usuario);
            if (clave == null) {
                throw new IllegalStateException("no se obtuvo la clave de " + usuario);
            }
            if (!seguridad.login(usuario, clave)) {
                System.out.println("Credenciales incorrectas. No hay sesión activa.");
            } else {
                System.out.println("Sesión iniciada: " + usuario);
            }
        } else if (sub.equals("logout")) {
            seguridad.logout();
            System.out.println("Sesión cerrada.");
        } else if (sub.equals("who")) {
            System.out.println(seguridad.haySesion()
                    ? "Usuario: " + seguridad.usuario()
                    : "No hay sesión activa.");
        } else if (sub.equals("users")) {
            StringBuilder sb = new StringBuilder("Usuarios de demostración: ");
            for (String u : seguridad.usuarios()) sb.append(u).append(' ');
            System.out.println(sb.toString().trim());
            System.out.println("Claves: admin/admin123, docente/docente, alumno/alumno, "
                    + "invitado/invitado, ana/ana, bruno/bruno.");
        } else {
            throw new IllegalArgumentException("Subcomando auth desconocido: " + args[1]);
        }
    }

    private void secCmd(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Uso: sec permiso|revocar|administrador|dispositivo|matriz|usuarios|cifrar|descifrar");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "permiso":
                exigir(args, 5, "sec permiso <usuario> <id|ruta> <rwx>");
                seguridad.conceder(args[2], resolverObjeto(args[3]), args[4]);
                System.out.println("Permiso concedido: " + args[2] + " -> " + args[3]
                        + " [" + args[4] + "]");
                break;
            case "revocar":
                exigir(args, 4, "sec revocar <usuario> <id|ruta>");
                seguridad.revocar(args[2], resolverObjeto(args[3]));
                System.out.println("Permisos revocados: " + args[2] + " -> " + args[3]);
                break;
            case "administrador":
                exigir(args, 3, "sec administrador <usuario>");
                seguridad.hacerAdministrador(args[2]);
                System.out.println("Administrador: " + args[2]);
                break;
            case "dispositivo":
                exigir(args, 5, "sec dispositivo <disco|terminal> <usuario> <rwx>");
                seguridad.concederDispositivo(args[2], args[3], args[4]);
                System.out.println("Dispositivo " + args[2] + " concedido a " + args[3]
                        + " [" + args[4] + "]");
                break;
            case "matriz":
                System.out.println(seguridad.control().listarMatriz());
                break;
            case "usuarios":
                authCmd(new String[] { "auth", "users" });
                break;
            case "cifrar": case "descifrar": {
                exigirFs();
                exigir(args, 3, "sec " + sub + " <ruta> [clave]");
                String clave = args.length >= 4 ? args[3]
                        : proveerSecreto("SOS_CLAVE_ARCHIVO");
                if (clave == null) {
                    throw new IllegalStateException("no se obtuvo la clave de cifrado");
                }
                if (sub.equals("cifrar")) {
                    seguridad.cifrar(fs, args[2], clave);
                    System.out.println("Cifrado: " + args[2]);
                } else {
                    boolean ok = seguridad.descifrar(fs, args[2], clave);
                    System.out.println(ok ? "Descifrado: " + args[2]
                            : "Clave incorrecta: el archivo no se modificó.");
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Subcomando sec desconocido: " + args[1]);
        }
    }

    private int resolverObjeto(String texto) throws Exception {
        String s = texto.trim();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        exigirFs();
        return fs.info(s).idObjeto;
    }

    // ==================================================================== apoyo

    /** Secretos: entorno primero; si no, petición sin eco. Nunca se escriben en el log. */
    private String proveerSecreto(String clave) {
        if (clave == null) return null;
        if (clave.startsWith("login:")) {
            String usuario = clave.substring("login:".length());
            String especifica = "SOS_CLAVE_" + usuario.toUpperCase(Locale.ROOT).replace('-', '_');
            String valor = System.getenv(especifica);
            if (valor != null) return valor;
            valor = System.getenv("SOS_CLAVE");
            if (valor != null) return valor;
            return pedirSecreto("Clave de " + usuario + ": ");
        }
        String valor = System.getenv(clave);
        if (valor != null) return valor;
        return pedirSecreto("Clave de cifrado (" + clave + "): ");
    }

    private String pedirSecreto(String mensaje) {
        Console consola = System.console();
        if (consola != null) {
            char[] chars = consola.readPassword(mensaje);
            return chars == null ? null : new String(chars);
        }
        System.err.print(mensaje);
        System.err.flush();
        try {
            if (teclado != null && teclado.hasNextLine()) return teclado.nextLine();
            BufferedReader lector = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return lector.readLine();
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo leer la clave", e);
        }
    }

    private static PoliticaDisco politica(String texto) {
        String p = texto.trim().toLowerCase(Locale.ROOT);
        if (p.equals("fcfs")) return PoliticaDisco.FCFS;
        if (p.equals("scan")) return PoliticaDisco.SCAN;
        if (p.equals("cscan") || p.equals("c-scan")) return PoliticaDisco.CSCAN;
        throw new IllegalArgumentException("política desconocida: " + texto);
    }

    private static void exigir(String[] args, int minimo, String uso) {
        if (args.length < minimo) throw new IllegalArgumentException("Uso: " + uso);
    }
}
