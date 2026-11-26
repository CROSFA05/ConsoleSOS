package com.itescasos.Escenarios;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.itescasos.IO.EscenarioIO;

/** Escenario validado: una sola carga de trabajo para los cinco módulos. */
public class Escenario {

    public static class ProcDef {
        public final String pid;
        public final int llegada;
        public final List<Integer> cpu;
        public final List<Integer> io;
        public final int prioridad;
        public final int memoriaBytes;
        public final Integer paginas;
        /** Operación de archivo que el proceso solicita al recuperar CPU (leer|escribir). */
        public final String operacionArchivo;
        public final String rutaArchivo;
        public final String textoArchivo;
        public final int linea;

        public ProcDef(String pid, int llegada, List<Integer> cpu, List<Integer> io,
                        int prioridad, int memoriaBytes, Integer paginas, String operacionArchivo,
                        String rutaArchivo, String textoArchivo, int linea) {
            this.pid = pid;
            this.llegada = llegada;
            this.cpu = cpu;
            this.io = io;
            this.prioridad = prioridad;
            this.memoriaBytes = memoriaBytes;
            this.paginas = paginas;
            this.operacionArchivo = operacionArchivo;
            this.rutaArchivo = rutaArchivo;
            this.textoArchivo = textoArchivo;
            this.linea = linea;
        }
    }

    public static class RefDef {
        public final String etiqueta;
        public final String pid;
        public final int t;
        public final int pagina;
        public final boolean escritura;
        public final int linea;

        public RefDef(String etiqueta, String pid, int t, int pagina, boolean escritura, int linea) {
            this.etiqueta = etiqueta;
            this.pid = pid;
            this.t = t;
            this.pagina = pagina;
            this.escritura = escritura;
            this.linea = linea;
        }
    }

    public static class Accion {
        public final String comando;
        public final List<String> argumentos;
        public final int linea;

        public Accion(String comando, List<String> argumentos, int linea) {
            this.comando = comando;
            this.argumentos = argumentos;
            this.linea = linea;
        }
    }

    public static class PermisoDef {
        public final String usuario;
        public final String objeto;
        public final String rwx;
        public final boolean revocar;
        public final int linea;

        public PermisoDef(String usuario, String objeto, String rwx, boolean revocar, int linea) {
            this.usuario = usuario;
            this.objeto = objeto;
            this.rwx = rwx;
            this.revocar = revocar;
            this.linea = linea;
        }
    }

    public static class DispositivoDef {
        public final String dispositivo;
        public final String usuario;
        public final String rwx;
        public final int linea;

        public DispositivoDef(String dispositivo, String usuario, String rwx, int linea) {
            this.dispositivo = dispositivo;
            this.usuario = usuario;
            this.rwx = rwx;
            this.linea = linea;
        }
    }

    public Path origen;

    // [procesos] / [planificacion]
    public final List<ProcDef> procesos = new ArrayList<ProcDef>();
    public String algoritmo = "fcfs";
    public int quantum = 1;
    public final List<String> compararCPU = new ArrayList<String>();

    // [memoria]
    public int marcosTotales = 3;
    public int tamPagina = 4096;
    public String reemplazo = "fifo";
    public int cargaPaginaTicks = 1;
    public String pidReferencias = "P1";
    public final List<RefDef> referencias = new ArrayList<RefDef>();
    public final List<String> compararMemoria = new ArrayList<String>();

    // [io] (parser reutilizado del módulo 4)
    public EscenarioIO io;
    public boolean tieneIo;

    // [archivos]
    public String imagen = "disk.img";
    public boolean formatear = false;
    public final List<Accion> archivos = new ArrayList<Accion>();

    // [seguridad]
    public String usuario;
    public String claveArchivo;
    public final List<PermisoDef> permisos = new ArrayList<PermisoDef>();
    public final List<DispositivoDef> dispositivos = new ArrayList<DispositivoDef>();
    public final List<String> administradores = new ArrayList<String>();

    /** Configuración declarada, para el registro reproducible sin secretos. */
    public final List<String> notas = new ArrayList<String>();

    public boolean tieneProcesos() { return !procesos.isEmpty(); }

    public ProcDef proceso(String pid) {
        for (ProcDef p : procesos) {
            if (p.pid.equals(pid)) return p;
        }
        return null;
    }
}
