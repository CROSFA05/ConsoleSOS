package com.itescasos.Archivos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.itescasos.IO.DispositivoBloques;
import com.itescasos.Seguridad.ControlAcceso;
import com.itescasos.Seguridad.Derecho;

import static com.itescasos.Archivos.ErrorFS.Codigo;

/**
 * Sistema de archivos educativo sobre un DispositivoBloques (normalmente una imagen persistente
 * accedida a través del driver de disco). No guarda en RAM ningún estado del volumen: todo se
 * reconstruye desde el superbloque, el mapa de bits, la tabla de inodos y los bloques.
 * Solo viven en memoria las aperturas (descriptor, objeto, modo, cursor).
 *
 * Contratos (decisiones documentadas):
 *  - Rutas absolutas ('/a/b'). Se resuelven '.' y '..' recorriendo el árbol sin salir de la raíz.
 *  - Identidad: cada objeto tiene un idObjeto estable que no se reutiliza (renombrar lo conserva;
 *    borrar y crear de nuevo produce otro id). Seguridad debe indexar permisos por ese id.
 *  - create ruta [n]: n es el TAMAÑO LÓGICO inicial; el archivo nace con n bytes en cero.
 *  - write (CLI) = reemplaza todo el contenido. escribir(fd) = escribe desde el cursor. agregar = al final.
 *  - rm: archivos, y directorios solo si están vacíos. Un archivo con descriptores abiertos NO se borra (ARCHIVO_EN_USO).
 *  - Toda validación y reserva de espacio ocurre ANTES de modificar el disco: un rechazo no deja cambios.
 *  - Orden de escritura: mapa de bits -> datos -> inodo -> entrada de directorio -> superbloque.
 *    Una interrupción deja, como mucho, bloques o inodos huérfanos que fsck recupera.
 *  - Permisos: LEER sobre el objeto para leer/listar; ESCRIBIR sobre el directorio padre para
 *    crear, borrar o renombrar; ESCRIBIR sobre el archivo para modificar. Cada read/write con
 *    descriptor vuelve a consultar el permiso vigente. EJECUTAR en directorios no se exige.
 */
public class SistemaArchivos {
    public static final int FLAG_CIFRADO = 1;

    private final DispositivoBloques dev;
    private final ControlAcceso control;
    private final Superbloque sb;
    private boolean sbModificado = false;
    private boolean montajeLimpio = true;

    private final Map<Integer, Apertura> aperturas = new LinkedHashMap<Integer, Apertura>();
    private int siguienteFd = 3;

    private static final class Apertura {
        final int fd;
        final int inodo;
        final int idObjeto;
        final String ruta;
        final ModoApertura modo;
        int cursor = 0;

        Apertura(int fd, int inodo, int idObjeto, String ruta, ModoApertura modo) {
            this.fd = fd;
            this.inodo = inodo;
            this.idObjeto = idObjeto;
            this.ruta = ruta;
            this.modo = modo;
        }
    }

    private SistemaArchivos(DispositivoBloques dev, ControlAcceso control, Superbloque sb) {
        this.dev = dev;
        this.control = control == null ? ControlAcceso.PERMITIR_TODO : control;
        this.sb = sb;
    }

    // ================================================================== formato y montaje

    /**
     * Da formato al dispositivo (operación explícita y destructiva). Crea superbloque, mapa de bits,
     * tabla de inodos, el directorio raíz y /lost+found (destino de objetos huérfanos de fsck).
     */
    public static void formatear(DispositivoBloques dev, int totalInodos) {
        Superbloque sb = Superbloque.calcular(dev.tamanoBloque(), dev.totalBloques(), totalInodos);
        int b = sb.tamBloque;
        byte[] cero = new byte[b];
        for (int i = 1; i < sb.inicioDatos; i++) {
            dev.escribirBloque(i, cero);
        }
        byte[] mapa = new byte[sb.bloquesBitmap * b];
        for (int i = 0; i < sb.inicioDatos; i++) {
            Formato.ponerBit(mapa, i, true);
        }
        for (int i = 0; i < sb.bloquesBitmap; i++) {
            dev.escribirBloque(sb.inicioBitmap + i, Arrays.copyOfRange(mapa, i * b, (i + 1) * b));
        }
        SistemaArchivos fs = new SistemaArchivos(dev, ControlAcceso.PERMITIR_TODO, sb);
        Inodo raiz = Inodo.vacio(Formato.RAIZ);
        raiz.tipo = Formato.TIPO_DIR;
        raiz.idObjeto = sb.proximoId++;
        fs.escribirInodo(raiz);
        sb.inodosLibres--;
        sb.estado = 0;
        fs.escribirSuperbloque();
        fs.mkdir("/" + Formato.LOST_FOUND);
        sb.estado = 1;
        fs.escribirSuperbloque();
    }

    /** Monta un volumen existente. Si la imagen no es válida falla con IMAGEN_INVALIDA sin tocarla. */
    public static SistemaArchivos montar(DispositivoBloques dev, ControlAcceso control) {
        Superbloque sb = Superbloque.leer(dev.leerBloque(0));
        String problema = sb.validarGeometria(dev.tamanoBloque(), dev.totalBloques());
        if (problema != null) {
            throw new ErrorFS(Codigo.IMAGEN_INVALIDA, problema);
        }
        SistemaArchivos fs = new SistemaArchivos(dev, control, sb);
        fs.montajeLimpio = sb.estado == 1;
        if (sb.estado == 1) {
            sb.estado = 0;
            fs.escribirSuperbloque();
        }
        return fs;
    }

    /** Cierra descriptores y marca el volumen como desmontado limpiamente. */
    public void desmontar() {
        aperturas.clear();
        sb.estado = 1;
        escribirSuperbloque();
    }

    /** false si el volumen se encontró montado/interrumpido al abrirlo (conviene ejecutar fsck). */
    public boolean fueDesmontadoLimpio() { return montajeLimpio; }

    public int tamBloque() { return sb.tamBloque; }

    // ================================================================== jerarquía

    public void mkdir(String ruta) {
        crear(ruta, Formato.TIPO_DIR, 0);
    }

    /** Crea un archivo de 'tamanoInicial' bytes en cero (tamaño lógico; los bloques se reservan de inmediato). */
    public void crearArchivo(String ruta, int tamanoInicial) {
        if (tamanoInicial < 0) {
            throw new ErrorFS(Codigo.OPERACION_INVALIDA, "tamaño inicial negativo: " + tamanoInicial);
        }
        crear(ruta, Formato.TIPO_ARCHIVO, tamanoInicial);
    }

    public void eliminar(String ruta) {
        Destino d = resolverPadre(ruta);
        Inodo padre = d.padre.objeto();
        autorizar(padre, Derecho.ESCRIBIR, d.padre.canonica());
        EntradaDir e = buscar(padre, d.nombre);
        if (e == null) {
            throw new ErrorFS(Codigo.NO_EXISTE, "no existe: " + d.rutaCompleta());
        }
        protegerLostFound(padre, d.nombre, "eliminar");
        Inodo obj = leerInodo(e.inodo);
        if (obj.esDir() && !entradas(obj).isEmpty()) {
            throw new ErrorFS(Codigo.DIRECTORIO_NO_VACIO, "el directorio no está vacío: " + d.rutaCompleta());
        }
        if (!obj.esDir() && estaAbierto(obj.numero)) {
            throw new ErrorFS(Codigo.ARCHIVO_EN_USO, "tiene descriptores abiertos: " + d.rutaCompleta());
        }

        // 1) el nombre desaparece, 2) se libera el inodo, 3) se liberan los bloques
        byte[] blq = dev.leerBloque(padre.bloques[e.bloqueLogico]);
        Directorio.borrar(blq, e.ranura);
        dev.escribirBloque(padre.bloques[e.bloqueLogico], blq);
        escribirInodo(Inodo.vacio(obj.numero));
        liberarBloques(obj.bloquesUsados());
        sb.inodosLibres++;
        sbModificado = true;
        confirmar();
        control.objetoEliminado(obj.idObjeto);
    }

    public void renombrar(String origen, String destino) {
        Destino o = resolverPadre(origen);
        Destino n = resolverPadre(destino);
        Inodo padreO = o.padre.objeto();
        Inodo padreN = n.padre.objeto();
        autorizar(padreO, Derecho.ESCRIBIR, o.padre.canonica());
        if (padreN.numero != padreO.numero) {
            autorizar(padreN, Derecho.ESCRIBIR, n.padre.canonica());
        }
        EntradaDir e = buscar(padreO, o.nombre);
        if (e == null) {
            throw new ErrorFS(Codigo.NO_EXISTE, "no existe: " + o.rutaCompleta());
        }
        protegerLostFound(padreO, o.nombre, "renombrar");
        if (!Directorio.nombreValido(n.nombre)) {
            throw new ErrorFS(Codigo.NOMBRE_INVALIDO, "nombre no válido: '" + n.nombre + "'");
        }
        if (buscar(padreN, n.nombre) != null) {
            throw new ErrorFS(Codigo.YA_EXISTE, "ya existe: " + n.rutaCompleta());
        }
        Inodo obj = leerInodo(e.inodo);
        if (obj.esDir()) {
            for (Inodo x : n.padre.pila) {
                if (x.numero == obj.numero) {
                    throw new ErrorFS(Codigo.OPERACION_INVALIDA,
                            "no se puede mover un directorio dentro de sí mismo ni de su descendiente");
                }
            }
        }

        if (padreO.numero == padreN.numero) { // mismo directorio: se reescribe el nombre en su ranura (un solo bloque)
            byte[] blq = dev.leerBloque(padreO.bloques[e.bloqueLogico]);
            Directorio.poner(blq, e.ranura, n.nombre, e.inodo);
            dev.escribirBloque(padreO.bloques[e.bloqueLogico], blq);
            return;
        }

        // directorio distinto: primero se agrega la entrada nueva, después se retira la vieja
        Hueco hueco = buscarHueco(padreN);
        int extra = hueco == null ? 1 : 0;
        if (hueco == null && padreN.numBloques >= Formato.MAX_DIRECTOS) {
            throw new ErrorFS(Codigo.DIRECTORIO_LLENO, "el directorio destino no admite más entradas");
        }
        int nuevoBloque = -1;
        if (extra == 1) {
            byte[] viejo = leerBitmap();
            if (libres(viejo) < 1) {
                throw new ErrorFS(Codigo.SIN_ESPACIO, "no hay un bloque libre para ampliar el directorio destino");
            }
            byte[] nuevo = viejo.clone();
            nuevoBloque = reservar(nuevo, 1)[0];
            guardarBitmap(viejo, nuevo);
        }
        agregarEntrada(padreN, hueco, nuevoBloque, n.nombre, e.inodo);
        byte[] blq = dev.leerBloque(padreO.bloques[e.bloqueLogico]);
        Directorio.borrar(blq, e.ranura);
        dev.escribirBloque(padreO.bloques[e.bloqueLogico], blq);
        confirmar();
    }

    /** Lista un directorio (o devuelve el propio archivo si la ruta es un archivo), ordenado por nombre. */
    public List<InfoArchivo> listar(String ruta) {
        Ruta r = recorrer(componentes(ruta));
        Inodo obj = r.objeto();
        if (!obj.esDir()) {
            autorizar(obj, Derecho.LEER, r.canonica());
            return Collections.singletonList(infoDe(obj, r.nombres.isEmpty() ? "/" : r.nombres.get(r.nombres.size() - 1), r.canonica()));
        }
        autorizar(obj, Derecho.LEER, r.canonica());
        List<EntradaDir> es = entradas(obj);
        Collections.sort(es, new Comparator<EntradaDir>() {
            @Override
            public int compare(EntradaDir a, EntradaDir b) { return a.nombre.compareTo(b.nombre); }
        });
        List<InfoArchivo> out = new ArrayList<InfoArchivo>();
        for (EntradaDir e : es) {
            String base = r.canonica().equals("/") ? "" : r.canonica();
            out.add(infoDe(leerInodo(e.inodo), e.nombre, base + "/" + e.nombre));
        }
        return out;
    }

    public InfoArchivo info(String ruta) {
        Ruta r = recorrer(componentes(ruta));
        Inodo obj = r.objeto();
        autorizar(obj, Derecho.LEER, r.canonica());
        return infoDe(obj, r.nombres.isEmpty() ? "/" : r.nombres.get(r.nombres.size() - 1), r.canonica());
    }

    // ================================================================== espacio

    public InfoEspacio espacio() {
        byte[] mapa = leerBitmap();
        int ocupados = 0;
        int libres = 0;
        for (int b = sb.inicioDatos; b < sb.totalBloques; b++) {
            if (Formato.bit(mapa, b)) {
                ocupados++;
            } else {
                libres++;
            }
        }
        int archivos = 0;
        int dirs = 0;
        int usados = 0;
        long frag = 0;
        int porBloque = sb.tamBloque / Formato.INODO_BYTES;
        for (int i = 0; i < sb.bloquesInodos; i++) {
            byte[] blq = dev.leerBloque(sb.inicioInodos + i);
            for (int j = 0; j < porBloque; j++) {
                int num = i * porBloque + j + 1;
                if (num > sb.totalInodos) {
                    break;
                }
                Inodo ino = Inodo.leer(num, blq, j * Formato.INODO_BYTES);
                if (!ino.enUso()) {
                    continue;
                }
                usados++;
                if (ino.esDir()) {
                    dirs++;
                } else {
                    archivos++;
                    frag += (long) ino.numBloques * sb.tamBloque - ino.tamano;
                }
            }
        }
        return new InfoEspacio(sb.tamBloque, sb.totalBloques, sb.inicioDatos, ocupados, libres,
                sb.totalInodos, sb.totalInodos - usados, archivos, dirs, frag);
    }

    // ================================================================== aperturas y acceso secuencial

    public int abrirArchivo(String ruta, ModoApertura modo) {
        Ruta r = recorrer(componentes(ruta));
        Inodo ino = r.objeto();
        if (ino.esDir()) {
            throw new ErrorFS(Codigo.ES_DIRECTORIO, "es un directorio: " + r.canonica());
        }
        if (modo.puedeLeer()) {
            autorizar(ino, Derecho.LEER, r.canonica());
        }
        if (modo.puedeEscribir()) {
            autorizar(ino, Derecho.ESCRIBIR, r.canonica());
        }
        int fd = siguienteFd++;
        aperturas.put(fd, new Apertura(fd, ino.numero, ino.idObjeto, r.canonica(), modo));
        return fd;
    }

    public void cerrarDescriptor(int fd) {
        if (aperturas.remove(fd) == null) {
            throw new ErrorFS(Codigo.DESCRIPTOR_INVALIDO, "descriptor desconocido: " + fd);
        }
    }

    public int descriptoresAbiertos() { return aperturas.size(); }

    public int cursor(int fd) { return apertura(fd).cursor; }

    /** Posiciona el cursor en [0, tamaño]; no se permiten huecos más allá del final. */
    public void posicionar(int fd, int posicion) {
        Apertura a = apertura(fd);
        Inodo ino = leerInodo(a.inodo);
        if (posicion < 0 || posicion > ino.tamano) {
            throw new ErrorFS(Codigo.OPERACION_INVALIDA, "posición " + posicion + " fuera de [0," + ino.tamano + "]");
        }
        a.cursor = posicion;
    }

    /** Lee hasta n bytes desde el cursor (solo hasta el tamaño lógico) y avanza el cursor. */
    public byte[] leer(int fd, int n) {
        Apertura a = apertura(fd);
        if (!a.modo.puedeLeer()) {
            throw new ErrorFS(Codigo.MODO_INVALIDO, "el descriptor " + fd + " no está abierto para lectura");
        }
        if (n < 0) {
            throw new ErrorFS(Codigo.OPERACION_INVALIDA, "cantidad negativa: " + n);
        }
        autorizarId(a.idObjeto, Derecho.LEER, a.ruta);
        Inodo ino = leerInodo(a.inodo);
        byte[] datos = leerRango(ino, a.cursor, n);
        a.cursor += datos.length;
        return datos;
    }

    /** Escribe desde el cursor (sobrescribe y/o extiende) y avanza el cursor. Todo o nada ante falta de espacio. */
    public int escribir(int fd, byte[] datos) {
        Apertura a = apertura(fd);
        if (!a.modo.puedeEscribir()) {
            throw new ErrorFS(Codigo.MODO_INVALIDO, "el descriptor " + fd + " no está abierto para escritura");
        }
        autorizarId(a.idObjeto, Derecho.ESCRIBIR, a.ruta);
        Inodo ino = leerInodo(a.inodo);
        int pos = Math.min(a.cursor, ino.tamano);
        escribirRango(ino, pos, datos);
        a.cursor = pos + datos.length;
        return datos.length;
    }

    // ---- conveniencias (una apertura interna por llamada)

    public byte[] leerTodo(String ruta) {
        int fd = abrirArchivo(ruta, ModoApertura.LECTURA);
        try {
            return leer(fd, Integer.MAX_VALUE);
        } finally {
            cerrarDescriptor(fd);
        }
    }

    /** Reemplaza TODO el contenido (semántica del comando fs write). Rechazo => sin cambios. */
    public void reemplazarContenido(String ruta, byte[] datos) {
        Inodo ino = archivoParaEscribir(ruta);
        guardarContenido(ino, datos, false, null, null);
    }

    /** Escribe al final del archivo. */
    public void agregar(String ruta, byte[] datos) {
        int fd = abrirArchivo(ruta, ModoApertura.ESCRITURA);
        try {
            Inodo ino = leerInodo(apertura(fd).inodo);
            posicionar(fd, ino.tamano);
            escribir(fd, datos);
        } finally {
            cerrarDescriptor(fd);
        }
    }

    /**
     * Reemplazo atómico para el módulo de seguridad (cifrar/descifrar): escribe el contenido nuevo
     * en bloques NUEVOS, cambia contenido, banderas y atributos con una única escritura del inodo y
     * solo después libera los bloques viejos. Si algo falla antes, el archivo anterior queda intacto.
     * Requiere espacio libre para la copia completa. banderas/atributos null = no cambiar.
     */
    public void reemplazarContenidoAtomico(String ruta, byte[] datos, Integer banderas, byte[] atributos) {
        if (atributos != null && atributos.length != Formato.ATRIBUTOS_BYTES) {
            throw new ErrorFS(Codigo.OPERACION_INVALIDA, "los atributos miden " + Formato.ATRIBUTOS_BYTES + " bytes");
        }
        Inodo ino = archivoParaEscribir(ruta);
        guardarContenido(ino, datos, true, banderas, atributos);
    }

    /** Cambia solo banderas y atributos (una escritura del inodo). */
    public void establecerAtributos(String ruta, int banderas, byte[] atributos) {
        if (atributos.length != Formato.ATRIBUTOS_BYTES) {
            throw new ErrorFS(Codigo.OPERACION_INVALIDA, "los atributos miden " + Formato.ATRIBUTOS_BYTES + " bytes");
        }
        Inodo ino = archivoParaEscribir(ruta);
        ino.flags = banderas;
        ino.atributos = atributos.clone();
        escribirInodo(ino);
    }

    // ================================================================== creación

    private void crear(String ruta, int tipo, int tamInicial) {
        Destino d = resolverPadre(ruta);
        Inodo padre = d.padre.objeto();
        autorizar(padre, Derecho.ESCRIBIR, d.padre.canonica());
        if (!Directorio.nombreValido(d.nombre)) {
            throw new ErrorFS(Codigo.NOMBRE_INVALIDO,
                    "nombre no válido (1..28 bytes, sin '/' ni controles): '" + d.nombre + "'");
        }
        if (buscar(padre, d.nombre) != null) {
            throw new ErrorFS(Codigo.YA_EXISTE, "ya existe: " + d.rutaCompleta());
        }
        int b = sb.tamBloque;
        int nBloques = Formato.techo(tamInicial, b);
        if (nBloques > Formato.MAX_DIRECTOS) {
            throw new ErrorFS(Codigo.ARCHIVO_DEMASIADO_GRANDE,
                    "máximo " + Formato.MAX_DIRECTOS * b + " bytes por archivo (" + Formato.MAX_DIRECTOS + " bloques de " + b + ")");
        }
        Hueco hueco = buscarHueco(padre);
        boolean ampliar = hueco == null;
        if (ampliar && padre.numBloques >= Formato.MAX_DIRECTOS) {
            throw new ErrorFS(Codigo.DIRECTORIO_LLENO, "el directorio no admite más entradas: " + d.padre.canonica());
        }
        int necesarios = nBloques + (ampliar ? 1 : 0);
        byte[] viejo = leerBitmap();
        if (libres(viejo) < necesarios) {
            throw new ErrorFS(Codigo.SIN_ESPACIO,
                    "se necesitan " + necesarios + " bloques y hay " + libres(viejo) + " libres");
        }
        Inodo nuevo = buscarInodoLibre();

        // ---- desde aquí se modifica el disco (todo lo que podía fallar ya se comprobó)
        byte[] mapa = viejo.clone();
        int[] reservados = reservar(mapa, necesarios);
        guardarBitmap(viejo, mapa);
        byte[] cero = new byte[b];
        for (int i = 0; i < nBloques; i++) {
            dev.escribirBloque(reservados[i], cero);
            nuevo.bloques[i] = reservados[i];
        }
        nuevo.tipo = tipo;
        nuevo.idObjeto = sb.proximoId++;
        nuevo.tamano = tipo == Formato.TIPO_ARCHIVO ? tamInicial : 0;
        nuevo.numBloques = nBloques;
        escribirInodo(nuevo);
        agregarEntrada(padre, hueco, ampliar ? reservados[nBloques] : -1, d.nombre, nuevo.numero);
        sb.inodosLibres--;
        sbModificado = true;
        confirmar();
        control.objetoCreado(nuevo.idObjeto, d.rutaCompleta());
    }

    // ================================================================== contenido

    private Inodo archivoParaEscribir(String ruta) {
        Ruta r = recorrer(componentes(ruta));
        Inodo ino = r.objeto();
        if (ino.esDir()) {
            throw new ErrorFS(Codigo.ES_DIRECTORIO, "es un directorio: " + r.canonica());
        }
        autorizar(ino, Derecho.ESCRIBIR, r.canonica());
        return ino;
    }

    /** Lectura que cruza bloques lógicos: bloque = pos / B, desplazamiento = pos % B. */
    private byte[] leerRango(Inodo ino, int pos, int n) {
        int b = sb.tamBloque;
        int disponible = Math.max(0, Math.min(n, ino.tamano - pos));
        byte[] out = new byte[disponible];
        int copiados = 0;
        while (copiados < disponible) {
            int p = pos + copiados;
            int lb = p / b;
            int off = p % b;
            int tomar = Math.min(b - off, disponible - copiados);
            byte[] blq = dev.leerBloque(ino.bloques[lb]);
            System.arraycopy(blq, off, out, copiados, tomar);
            copiados += tomar;
        }
        return out;
    }

    /** Escribe 'datos' desde 'pos' (pos <= tamaño). Reserva primero; solo toca los bloques afectados. */
    private void escribirRango(Inodo ino, int pos, byte[] datos) {
        if (datos.length == 0) {
            return;
        }
        int b = sb.tamBloque;
        int nuevoTam = Math.max(ino.tamano, pos + datos.length);
        int n1 = ino.numBloques;
        int n2 = Formato.techo(nuevoTam, b);
        if (n2 > Formato.MAX_DIRECTOS) {
            throw new ErrorFS(Codigo.ARCHIVO_DEMASIADO_GRANDE,
                    "el archivo superaría el máximo de " + Formato.MAX_DIRECTOS * b + " bytes");
        }
        int extra = n2 - n1;
        if (extra > 0) {
            byte[] viejo = leerBitmap();
            if (libres(viejo) < extra) {
                throw new ErrorFS(Codigo.SIN_ESPACIO, "se necesitan " + extra + " bloques y hay " + libres(viejo) + " libres");
            }
            byte[] mapa = viejo.clone();
            int[] nuevos = reservar(mapa, extra);
            guardarBitmap(viejo, mapa);
            for (int i = 0; i < extra; i++) {
                ino.bloques[n1 + i] = nuevos[i];
            }
        }
        int primero = pos / b;
        int ultimo = (pos + datos.length - 1) / b;
        for (int lb = primero; lb <= ultimo; lb++) {
            int ini = Math.max(pos, lb * b);
            int fin = Math.min(pos + datos.length, (lb + 1) * b);
            boolean completo = ini == lb * b && fin == (lb + 1) * b;
            byte[] blq = (lb >= n1 || completo) ? new byte[b] : dev.leerBloque(ino.bloques[lb]);
            System.arraycopy(datos, ini - pos, blq, ini - lb * b, fin - ini);
            dev.escribirBloque(ino.bloques[lb], blq);
        }
        ino.numBloques = n2;
        ino.tamano = nuevoTam;
        escribirInodo(ino);
        confirmar();
    }

    /** Reemplaza todo el contenido. Con 'sombra' usa bloques nuevos y libera los viejos al final. */
    private void guardarContenido(Inodo ino, byte[] datos, boolean sombra, Integer banderas, byte[] atributos) {
        int b = sb.tamBloque;
        int n1 = ino.numBloques;
        int n2 = Formato.techo(datos.length, b);
        if (n2 > Formato.MAX_DIRECTOS) {
            throw new ErrorFS(Codigo.ARCHIVO_DEMASIADO_GRANDE, "máximo " + Formato.MAX_DIRECTOS * b + " bytes por archivo");
        }
        int reutiliza = sombra ? 0 : Math.min(n1, n2);
        int nuevos = n2 - reutiliza;
        int[] reservados = new int[0];
        if (nuevos > 0) {
            byte[] viejo = leerBitmap();
            if (libres(viejo) < nuevos) {
                throw new ErrorFS(Codigo.SIN_ESPACIO, "se necesitan " + nuevos + " bloques y hay " + libres(viejo) + " libres");
            }
            byte[] mapa = viejo.clone();
            reservados = reservar(mapa, nuevos);
            guardarBitmap(viejo, mapa);
        }
        int[] finales = new int[n2];
        for (int i = 0; i < reutiliza; i++) {
            finales[i] = ino.bloques[i];
        }
        for (int i = 0; i < nuevos; i++) {
            finales[reutiliza + i] = reservados[i];
        }
        for (int i = 0; i < n2; i++) {
            byte[] blq = new byte[b];
            int ini = i * b;
            System.arraycopy(datos, ini, blq, 0, Math.min(b, datos.length - ini));
            dev.escribirBloque(finales[i], blq);
        }
        int[] aLiberar = Arrays.copyOfRange(ino.bloques, reutiliza, n1);
        Arrays.fill(ino.bloques, 0);
        System.arraycopy(finales, 0, ino.bloques, 0, n2);
        ino.numBloques = n2;
        ino.tamano = datos.length;
        if (banderas != null) {
            ino.flags = banderas;
        }
        if (atributos != null) {
            ino.atributos = atributos.clone();
        }
        escribirInodo(ino); // punto de confirmación: una sola escritura de bloque
        liberarBloques(aLiberar);
        confirmar();
    }

    // ================================================================== directorios

    private static final class Hueco {
        final int bloqueLogico;
        final int ranura;
        final byte[] contenido;

        Hueco(int bloqueLogico, int ranura, byte[] contenido) {
            this.bloqueLogico = bloqueLogico;
            this.ranura = ranura;
            this.contenido = contenido;
        }
    }

    private List<EntradaDir> entradas(Inodo dir) {
        List<EntradaDir> out = new ArrayList<EntradaDir>();
        for (int lb = 0; lb < dir.numBloques; lb++) {
            out.addAll(Directorio.entradas(dev.leerBloque(dir.bloques[lb]), lb));
        }
        return out;
    }

    private EntradaDir buscar(Inodo dir, String nombre) {
        for (EntradaDir e : entradas(dir)) {
            if (e.nombre.equals(nombre)) {
                return e;
            }
        }
        return null;
    }

    private Hueco buscarHueco(Inodo dir) {
        for (int lb = 0; lb < dir.numBloques; lb++) {
            byte[] blq = dev.leerBloque(dir.bloques[lb]);
            int r = Directorio.ranuraLibre(blq);
            if (r >= 0) {
                return new Hueco(lb, r, blq);
            }
        }
        return null;
    }

    /** Inserta la entrada en el hueco encontrado o, si no había, en el bloque nuevo ya reservado. */
    private void agregarEntrada(Inodo dir, Hueco hueco, int bloqueNuevo, String nombre, int inodo) {
        if (hueco != null) {
            Directorio.poner(hueco.contenido, hueco.ranura, nombre, inodo);
            dev.escribirBloque(dir.bloques[hueco.bloqueLogico], hueco.contenido);
        } else {
            byte[] blq = new byte[sb.tamBloque];
            Directorio.poner(blq, 0, nombre, inodo);
            dev.escribirBloque(bloqueNuevo, blq);
            dir.bloques[dir.numBloques++] = bloqueNuevo;
            dir.tamano = dir.numBloques * sb.tamBloque;
            escribirInodo(dir);
        }
    }

    private void protegerLostFound(Inodo padre, String nombre, String accion) {
        if (padre.numero == Formato.RAIZ && nombre.equals(Formato.LOST_FOUND)) {
            throw new ErrorFS(Codigo.OPERACION_INVALIDA, "/" + Formato.LOST_FOUND + " es del sistema: no se puede " + accion);
        }
    }

    private boolean estaAbierto(int inodo) {
        for (Apertura a : aperturas.values()) {
            if (a.inodo == inodo) {
                return true;
            }
        }
        return false;
    }

    private Apertura apertura(int fd) {
        Apertura a = aperturas.get(fd);
        if (a == null) {
            throw new ErrorFS(Codigo.DESCRIPTOR_INVALIDO, "descriptor desconocido: " + fd);
        }
        return a;
    }

    // ================================================================== rutas

    private static final class Ruta {
        final List<Inodo> pila = new ArrayList<Inodo>();
        final List<String> nombres = new ArrayList<String>();

        Inodo objeto() { return pila.get(pila.size() - 1); }

        String canonica() {
            if (nombres.isEmpty()) {
                return "/";
            }
            StringBuilder sb = new StringBuilder();
            for (String n : nombres) {
                sb.append('/').append(n);
            }
            return sb.toString();
        }
    }

    private static final class Destino {
        final Ruta padre;
        final String nombre;

        Destino(Ruta padre, String nombre) {
            this.padre = padre;
            this.nombre = nombre;
        }

        String rutaCompleta() {
            return padre.canonica().equals("/") ? "/" + nombre : padre.canonica() + "/" + nombre;
        }
    }

    private List<String> componentes(String ruta) {
        if (ruta == null || ruta.isEmpty() || ruta.charAt(0) != '/') {
            throw new ErrorFS(Codigo.RUTA_INVALIDA, "la ruta debe ser absoluta (empezar con '/'): " + ruta);
        }
        if (ruta.indexOf('\0') >= 0) {
            throw new ErrorFS(Codigo.RUTA_INVALIDA, "la ruta contiene un carácter nulo");
        }
        List<String> out = new ArrayList<String>();
        for (String c : ruta.split("/")) {
            if (!c.isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    /** Recorre componente a componente; '..' sube un nivel real (nunca más allá de la raíz). */
    private Ruta recorrer(List<String> comps) {
        Ruta r = new Ruta();
        r.pila.add(leerInodo(Formato.RAIZ));
        for (String c : comps) {
            Inodo actual = r.objeto();
            if (!actual.esDir()) {
                throw new ErrorFS(Codigo.NO_ES_DIRECTORIO, "un componente intermedio no es un directorio: " + r.canonica());
            }
            if (c.equals(".")) {
                continue;
            }
            if (c.equals("..")) {
                if (r.pila.size() > 1) {
                    r.pila.remove(r.pila.size() - 1);
                    r.nombres.remove(r.nombres.size() - 1);
                }
                continue;
            }
            EntradaDir e = buscar(actual, c);
            if (e == null) {
                String base = r.canonica().equals("/") ? "" : r.canonica();
                throw new ErrorFS(Codigo.NO_EXISTE, "no existe: " + base + "/" + c);
            }
            r.pila.add(leerInodo(e.inodo));
            r.nombres.add(c);
        }
        return r;
    }

    private Destino resolverPadre(String ruta) {
        List<String> comps = componentes(ruta);
        if (comps.isEmpty()) {
            throw new ErrorFS(Codigo.OPERACION_INVALIDA, "la raíz no tiene nombre ni directorio padre");
        }
        String nombre = comps.get(comps.size() - 1);
        if (nombre.equals(".") || nombre.equals("..")) {
            throw new ErrorFS(Codigo.NOMBRE_INVALIDO, "el último componente no puede ser '" + nombre + "'");
        }
        Ruta padre = recorrer(comps.subList(0, comps.size() - 1));
        if (!padre.objeto().esDir()) {
            throw new ErrorFS(Codigo.NO_ES_DIRECTORIO, "no es un directorio: " + padre.canonica());
        }
        return new Destino(padre, nombre);
    }

    // ================================================================== permisos

    private void autorizar(Inodo ino, Derecho d, String ruta) {
        autorizarId(ino.idObjeto, d, ruta);
    }

    private void autorizarId(int idObjeto, Derecho d, String ruta) {
        if (!control.autorizar(idObjeto, d, ruta)) {
            throw new ErrorFS(Codigo.PERMISO_DENEGADO, d + " denegado sobre " + ruta);
        }
    }

    // ================================================================== bajo nivel: inodos, mapa de bits, superbloque

    Inodo leerInodo(int numero) {
        if (numero < 1 || numero > sb.totalInodos) {
            throw new ErrorFS(Codigo.IMAGEN_INVALIDA, "inodo fuera de rango: " + numero);
        }
        int porBloque = sb.tamBloque / Formato.INODO_BYTES;
        int idx = numero - 1;
        byte[] blq = dev.leerBloque(sb.inicioInodos + idx / porBloque);
        return Inodo.leer(numero, blq, (idx % porBloque) * Formato.INODO_BYTES);
    }

    private void escribirInodo(Inodo ino) {
        int porBloque = sb.tamBloque / Formato.INODO_BYTES;
        int idx = ino.numero - 1;
        int bloque = sb.inicioInodos + idx / porBloque;
        byte[] blq = dev.leerBloque(bloque);
        ino.escribir(blq, (idx % porBloque) * Formato.INODO_BYTES);
        dev.escribirBloque(bloque, blq);
    }

    private Inodo buscarInodoLibre() {
        int porBloque = sb.tamBloque / Formato.INODO_BYTES;
        for (int i = 0; i < sb.bloquesInodos; i++) {
            byte[] blq = dev.leerBloque(sb.inicioInodos + i);
            for (int j = 0; j < porBloque; j++) {
                int num = i * porBloque + j + 1;
                if (num > sb.totalInodos) {
                    break;
                }
                if ((blq[j * Formato.INODO_BYTES] & 0xFF) == Formato.TIPO_LIBRE) {
                    return Inodo.vacio(num);
                }
            }
        }
        throw new ErrorFS(Codigo.SIN_INODOS, "no quedan inodos libres");
    }

    private byte[] leerBitmap() {
        int b = sb.tamBloque;
        byte[] mapa = new byte[sb.bloquesBitmap * b];
        for (int i = 0; i < sb.bloquesBitmap; i++) {
            System.arraycopy(dev.leerBloque(sb.inicioBitmap + i), 0, mapa, i * b, b);
        }
        return mapa;
    }

    /** Escribe solo los bloques del mapa que cambiaron y actualiza el contador de libres. */
    private void guardarBitmap(byte[] viejo, byte[] nuevo) {
        int b = sb.tamBloque;
        for (int i = 0; i < sb.bloquesBitmap; i++) {
            byte[] a = Arrays.copyOfRange(viejo, i * b, (i + 1) * b);
            byte[] n = Arrays.copyOfRange(nuevo, i * b, (i + 1) * b);
            if (!Arrays.equals(a, n)) {
                dev.escribirBloque(sb.inicioBitmap + i, n);
            }
        }
        sb.bloquesLibres = libres(nuevo);
        sbModificado = true;
    }

    private int libres(byte[] mapa) {
        int n = 0;
        for (int b = sb.inicioDatos; b < sb.totalBloques; b++) {
            if (!Formato.bit(mapa, b)) {
                n++;
            }
        }
        return n;
    }

    /** Marca como ocupados los primeros 'n' bloques de datos libres (primer ajuste) y los devuelve. */
    private int[] reservar(byte[] mapa, int n) {
        int[] r = new int[n];
        int k = 0;
        for (int b = sb.inicioDatos; b < sb.totalBloques && k < n; b++) {
            if (!Formato.bit(mapa, b)) {
                Formato.ponerBit(mapa, b, true);
                r[k++] = b;
            }
        }
        if (k < n) {
            throw new ErrorFS(Codigo.SIN_ESPACIO, "no hay bloques libres suficientes");
        }
        return r;
    }

    private void liberarBloques(int[] bloques) {
        if (bloques.length == 0) {
            return;
        }
        byte[] viejo = leerBitmap();
        byte[] mapa = viejo.clone();
        for (int blq : bloques) {
            if (blq >= sb.inicioDatos && blq < sb.totalBloques) {
                Formato.ponerBit(mapa, blq, false);
            }
        }
        guardarBitmap(viejo, mapa);
    }

    private void escribirSuperbloque() {
        dev.escribirBloque(0, sb.aBytes());
        sbModificado = false;
    }

    private void confirmar() {
        if (sbModificado) {
            escribirSuperbloque();
        }
    }

    private InfoArchivo infoDe(Inodo ino, String nombre, String ruta) {
        List<Integer> blqs = new ArrayList<Integer>();
        for (int b : ino.bloquesUsados()) {
            blqs.add(b);
        }
        return new InfoArchivo(nombre, ruta, ino.esDir() ? TipoObjeto.DIRECTORIO : TipoObjeto.ARCHIVO,
                ino.numero, ino.idObjeto, ino.tamano, ino.flags, blqs, ino.atributos);
    }
}
