package com.itescasos.Archivos;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.itescasos.IO.DispositivoBloques;

/**
 * Comprobación y reparación conservadora del volumen. Trabaja sobre el dispositivo directamente
 * (el volumen debe estar desmontado). Recorre superbloque, inodos, directorios y mapa de bits.
 *
 * Reparaciones (solo con reparar=true, y solo si las referencias son válidas y sin conflicto):
 *   - bits de metadatos apagados; bloque referenciado marcado libre (se enciende);
 *   - bloque marcado ocupado sin dueño = fuga (se libera);
 *   - entrada de directorio que apunta a un inodo libre o inexistente (se borra la entrada);
 *   - inodo válido sin nombre (huérfano) se reconecta en /lost+found como "#<idObjeto>";
 *   - contadores del superbloque y próximo identificador.
 * Quedan PENDIENTES (se informan, no se tocan): superbloque o raíz dañados, inodos con punteros
 * fuera de rango, bloques asignados a dos objetos, inodos referenciados dos veces, nombres repetidos.
 * fsck no reconstruye bytes perdidos: para eso solo sirve un respaldo.
 */
public final class Fsck {
    private final DispositivoBloques dev;
    private final boolean reparar;
    private final ReporteFsck rep = new ReporteFsck();
    private Superbloque sb;
    private final Map<Integer, byte[]> cache = new HashMap<Integer, byte[]>();
    private final Set<Integer> sucios = new TreeSet<Integer>();
    private Inodo[] inodos;
    private boolean[] valido;
    private boolean[] visitado;
    private byte[] mapa;

    private Fsck(DispositivoBloques dev, boolean reparar) {
        this.dev = dev;
        this.reparar = reparar;
    }

    public static ReporteFsck ejecutar(DispositivoBloques dev, boolean reparar) {
        return new Fsck(dev, reparar).correr();
    }

    private void hallazgo(String codigo, String desc, boolean reparable) {
        rep.agregar(codigo, desc, reparable ? (reparar ? ReporteFsck.Estado.REPARADO : ReporteFsck.Estado.DETECTADO)
                : ReporteFsck.Estado.PENDIENTE);
    }

    private ReporteFsck correr() {
        byte[] bloque0 = dev.leerBloque(0);
        try {
            sb = Superbloque.leer(bloque0);
        } catch (ErrorFS e) {
            rep.agregar("SUPERBLOQUE_INVALIDO", e.getMessage(), ReporteFsck.Estado.PENDIENTE);
            rep.marcarFatal();
            return rep;
        }
        String g = sb.validarGeometria(dev.tamanoBloque(), dev.totalBloques());
        if (g != null) {
            rep.agregar("GEOMETRIA_INVALIDA", g, ReporteFsck.Estado.PENDIENTE);
            rep.marcarFatal();
            return rep;
        }
        if (sb.estado == 0) {
            rep.agregar("NO_DESMONTADO_LIMPIAMENTE", "el volumen estaba montado o se interrumpió una operación", ReporteFsck.Estado.INFORMATIVO);
        }
        cargarInodos();
        int b = sb.tamBloque;
        mapa = new byte[sb.bloquesBitmap * b];
        for (int i = 0; i < sb.bloquesBitmap; i++) {
            System.arraycopy(bloque(sb.inicioBitmap + i), 0, mapa, i * b, b);
        }
        final byte[] mapaOriginal = mapa.clone();

        // 1) regiones reservadas siempre ocupadas
        for (int i = 0; i < sb.inicioDatos; i++) {
            if (!Formato.bit(mapa, i)) {
                hallazgo("BITMAP_METADATOS", "el bloque de metadatos " + i + " figuraba libre", true);
                Formato.ponerBit(mapa, i, true);
            }
        }

        // 2) validez estructural de cada inodo en uso
        boolean hayInvalidos = validarInodos();
        if (!valido[Formato.RAIZ] || !inodos[Formato.RAIZ].esDir()) {
            rep.agregar("RAIZ_INVALIDA", "el inodo raíz no es un directorio válido; solo un respaldo lo recupera", ReporteFsck.Estado.PENDIENTE);
            rep.marcarFatal();
            return rep;
        }

        // 3) recorrido desde la raíz
        visitado[Formato.RAIZ] = true;
        caminar(Formato.RAIZ);

        // 4) huérfanos: inodos válidos sin ningún nombre
        recuperarHuerfanos();
        for (int n = 1; n <= sb.totalInodos; n++) {
            if (inodos[n].enUso() && !visitado[n] && valido[n]) {
                rep.agregar("INODO_INALCANZABLE", "el inodo " + n + " (id " + inodos[n].idObjeto + ") no es alcanzable desde la raíz", ReporteFsck.Estado.PENDIENTE);
            }
        }

        // 5) mapa de bits esperado frente al real
        int[] duenos = new int[sb.totalBloques];
        int[] dueno = new int[sb.totalBloques];
        for (int n = 1; n <= sb.totalInodos; n++) {
            if (inodos[n].enUso() && valido[n]) {
                for (int blq : inodos[n].bloquesUsados()) {
                    duenos[blq]++;
                    dueno[blq] = n;
                }
            }
        }
        for (int blq = sb.inicioDatos; blq < sb.totalBloques; blq++) {
            boolean marcado = Formato.bit(mapa, blq);
            if (duenos[blq] > 1) {
                rep.agregar("BLOQUE_COMPARTIDO", "el bloque " + blq + " está asignado a " + duenos[blq] + " objetos; no se puede decidir su dueño", ReporteFsck.Estado.PENDIENTE);
                if (!marcado) {
                    Formato.ponerBit(mapa, blq, true);
                }
            } else if (duenos[blq] == 1 && !marcado) {
                hallazgo("BLOQUE_EN_USO_MARCADO_LIBRE", "el bloque " + blq + " (inodo " + dueno[blq] + ", id "
                        + inodos[dueno[blq]].idObjeto + ") está referenciado pero figuraba libre en el mapa de bits", true);
                Formato.ponerBit(mapa, blq, true);
            } else if (duenos[blq] == 0 && marcado) {
                if (hayInvalidos) {
                    rep.agregar("BLOQUE_SIN_DUENO", "el bloque " + blq + " figura ocupado sin dueño conocido; no se libera porque hay inodos dañados", ReporteFsck.Estado.PENDIENTE);
                } else {
                    hallazgo("BLOQUE_HUERFANO", "el bloque " + blq + " figuraba ocupado pero ningún objeto lo referencia (fuga)", true);
                    Formato.ponerBit(mapa, blq, false);
                }
            }
        }

        // 6) contadores del superbloque
        int libres = 0;
        for (int blq = sb.inicioDatos; blq < sb.totalBloques; blq++) {
            if (!Formato.bit(mapa, blq)) {
                libres++;
            }
        }
        int inodosLibres = 0;
        int maxId = 0;
        Set<Integer> ids = new HashSet<Integer>();
        for (int n = 1; n <= sb.totalInodos; n++) {
            if (!inodos[n].enUso()) {
                inodosLibres++;
            } else {
                maxId = Math.max(maxId, inodos[n].idObjeto);
                if (!ids.add(inodos[n].idObjeto)) {
                    rep.agregar("ID_DUPLICADO", "el idObjeto " + inodos[n].idObjeto + " aparece en más de un inodo", ReporteFsck.Estado.PENDIENTE);
                }
            }
        }
        if (sb.bloquesLibres != libres || sb.inodosLibres != inodosLibres || sb.proximoId <= maxId) {
            hallazgo("CONTADORES", "superbloque: bloques libres " + sb.bloquesLibres + " -> " + libres
                    + ", inodos libres " + sb.inodosLibres + " -> " + inodosLibres
                    + ", próximo id " + sb.proximoId + " -> " + Math.max(sb.proximoId, maxId + 1), true);
            sb.bloquesLibres = libres;
            sb.inodosLibres = inodosLibres;
            sb.proximoId = Math.max(sb.proximoId, maxId + 1);
        }

        // 7) escritura (solo en modo reparar)
        if (reparar) {
            if (rep.contar(ReporteFsck.Estado.PENDIENTE) == 0) {
                sb.estado = 1;
            }
            for (int blqN : sucios) {
                dev.escribirBloque(blqN, cache.get(blqN));
            }
            for (int i = 0; i < sb.bloquesBitmap; i++) {
                byte[] a = Arrays.copyOfRange(mapaOriginal, i * b, (i + 1) * b);
                byte[] n = Arrays.copyOfRange(mapa, i * b, (i + 1) * b);
                if (!Arrays.equals(a, n)) {
                    dev.escribirBloque(sb.inicioBitmap + i, n);
                }
            }
            byte[] nuevo0 = sb.aBytes();
            if (!Arrays.equals(nuevo0, bloque0)) {
                dev.escribirBloque(0, nuevo0);
            }
        }
        return rep;
    }

    // ------------------------------------------------------------ piezas

    private byte[] bloque(int n) {
        byte[] b = cache.get(n);
        if (b == null) {
            b = dev.leerBloque(n);
            cache.put(n, b);
        }
        return b;
    }

    private void sucio(int n) { sucios.add(n); }

    private void cargarInodos() {
        inodos = new Inodo[sb.totalInodos + 1];
        valido = new boolean[sb.totalInodos + 1];
        visitado = new boolean[sb.totalInodos + 1];
        int porBloque = sb.tamBloque / Formato.INODO_BYTES;
        for (int n = 1; n <= sb.totalInodos; n++) {
            int idx = n - 1;
            inodos[n] = Inodo.leer(n, bloque(sb.inicioInodos + idx / porBloque), (idx % porBloque) * Formato.INODO_BYTES);
        }
    }

    private boolean validarInodos() {
        boolean hay = false;
        for (int n = 1; n <= sb.totalInodos; n++) {
            Inodo i = inodos[n];
            if (!i.enUso()) {
                continue;
            }
            String motivo = null;
            if (i.tipo != Formato.TIPO_ARCHIVO && i.tipo != Formato.TIPO_DIR) {
                motivo = "tipo desconocido " + i.tipo;
            } else if (i.idObjeto <= 0) {
                motivo = "idObjeto inválido";
            } else if (i.numBloques < 0 || i.numBloques > Formato.MAX_DIRECTOS) {
                motivo = "numBloques fuera de rango (" + i.numBloques + ")";
            } else {
                for (int k = 0; k < i.numBloques && motivo == null; k++) {
                    if (i.bloques[k] < sb.inicioDatos || i.bloques[k] >= sb.totalBloques) {
                        motivo = "puntero fuera de rango (" + i.bloques[k] + ")";
                    }
                }
                if (motivo == null) {
                    if (i.esDir() && i.tamano != i.numBloques * sb.tamBloque) {
                        motivo = "tamaño de directorio incoherente";
                    } else if (!i.esDir() && (i.tamano < 0 || Formato.techo(i.tamano, sb.tamBloque) != i.numBloques)) {
                        motivo = "tamaño (" + i.tamano + ") incoherente con " + i.numBloques + " bloques";
                    }
                }
            }
            if (motivo == null) {
                valido[n] = true;
            } else {
                hay = true;
                rep.agregar("INODO_INVALIDO", "inodo " + n + ": " + motivo, ReporteFsck.Estado.PENDIENTE);
            }
        }
        return hay;
    }

    private void caminar(int dirNum) {
        Inodo dir = inodos[dirNum];
        Set<String> nombres = new HashSet<String>();
        for (int lb = 0; lb < dir.numBloques; lb++) {
            int bn = dir.bloques[lb];
            byte[] blq = bloque(bn);
            for (EntradaDir e : Directorio.entradas(blq, lb)) {
                if (e.inodo < 1 || e.inodo > sb.totalInodos || !inodos[e.inodo].enUso()) {
                    hallazgo("ENTRADA_INVALIDA", "la entrada '" + e.nombre + "' del directorio (inodo " + dirNum
                            + ") apunta al inodo " + e.inodo + " que no está en uso", true);
                    Directorio.borrar(blq, e.ranura);
                    sucio(bn);
                    continue;
                }
                if (!nombres.add(e.nombre)) {
                    rep.agregar("NOMBRE_DUPLICADO", "'" + e.nombre + "' repetido en el directorio (inodo " + dirNum + ")", ReporteFsck.Estado.PENDIENTE);
                }
                if (visitado[e.inodo]) {
                    rep.agregar("INODO_MULTIREFERENCIADO", "el inodo " + e.inodo + " tiene más de un nombre (entrada '" + e.nombre + "')", ReporteFsck.Estado.PENDIENTE);
                    continue;
                }
                visitado[e.inodo] = true;
                if (inodos[e.inodo].esDir() && valido[e.inodo]) {
                    caminar(e.inodo);
                }
            }
        }
    }

    private void recuperarHuerfanos() {
        // inodos que alguna entrada (de cualquier directorio válido) todavía nombra
        Set<Integer> nombrados = new HashSet<Integer>();
        for (int n = 1; n <= sb.totalInodos; n++) {
            if (inodos[n].enUso() && valido[n] && inodos[n].esDir()) {
                for (int lb = 0; lb < inodos[n].numBloques; lb++) {
                    for (EntradaDir e : Directorio.entradas(bloque(inodos[n].bloques[lb]), lb)) {
                        nombrados.add(e.inodo);
                    }
                }
            }
        }
        int lf = -1;
        Inodo raiz = inodos[Formato.RAIZ];
        for (int lb = 0; lb < raiz.numBloques && lf < 0; lb++) {
            for (EntradaDir e : Directorio.entradas(bloque(raiz.bloques[lb]), lb)) {
                if (e.nombre.equals(Formato.LOST_FOUND) && e.inodo >= 1 && e.inodo <= sb.totalInodos
                        && inodos[e.inodo].esDir() && valido[e.inodo]) {
                    lf = e.inodo;
                }
            }
        }
        for (int n = 1; n <= sb.totalInodos; n++) {
            if (!inodos[n].enUso() || visitado[n] || !valido[n] || nombrados.contains(n)) {
                continue;
            }
            String nombre = "#" + inodos[n].idObjeto;
            if (lf < 0 || !insertarEnLostFound(lf, nombre, n)) {
                rep.agregar("HUERFANO_SIN_RECONECTAR", "el inodo " + n + " (id " + inodos[n].idObjeto
                        + ") no tiene nombre y no se pudo reconectar en /" + Formato.LOST_FOUND, ReporteFsck.Estado.PENDIENTE);
                continue;
            }
            hallazgo("HUERFANO_RECONECTADO", "el inodo " + n + " (id " + inodos[n].idObjeto + ") no tenía nombre; queda en /"
                    + Formato.LOST_FOUND + "/" + nombre, true);
            visitado[n] = true;
            if (inodos[n].esDir()) {
                caminar(n);
            }
        }
    }

    private boolean insertarEnLostFound(int lfNum, String nombre, int inodo) {
        Inodo lf = inodos[lfNum];
        for (int lb = 0; lb < lf.numBloques; lb++) {
            byte[] blq = bloque(lf.bloques[lb]);
            int r = Directorio.ranuraLibre(blq);
            if (r >= 0) {
                Directorio.poner(blq, r, nombre, inodo);
                sucio(lf.bloques[lb]);
                return true;
            }
        }
        if (lf.numBloques >= Formato.MAX_DIRECTOS) {
            return false;
        }
        Set<Integer> usados = new HashSet<Integer>();
        for (int n = 1; n <= sb.totalInodos; n++) {
            if (inodos[n].enUso() && valido[n]) {
                for (int b : inodos[n].bloquesUsados()) {
                    usados.add(b);
                }
            }
        }
        for (int b = sb.inicioDatos; b < sb.totalBloques; b++) {
            if (!usados.contains(b)) {
                byte[] blq = new byte[sb.tamBloque];
                Directorio.poner(blq, 0, nombre, inodo);
                cache.put(b, blq);
                sucio(b);
                Formato.ponerBit(mapa, b, true);
                lf.bloques[lf.numBloques++] = b;
                lf.tamano = lf.numBloques * sb.tamBloque;
                int porBloque = sb.tamBloque / Formato.INODO_BYTES;
                int bi = sb.inicioInodos + (lfNum - 1) / porBloque;
                lf.escribir(bloque(bi), ((lfNum - 1) % porBloque) * Formato.INODO_BYTES);
                sucio(bi);
                return true;
            }
        }
        return false;
    }
}
