package com.itescasos.Archivos;

/**
 * Reporte de espacio. Invariante: datosLibres + datosOcupados == totalBloques - reservadosMetadatos.
 * Entre los ocupados se cuentan los bloques de directorios.
 */
public final class InfoEspacio {
    public final int tamBloque;
    public final int totalBloques;
    public final int reservadosMetadatos;
    public final int datosOcupados;
    public final int datosLibres;
    public final int inodosTotal;
    public final int inodosLibres;
    public final int archivos;
    public final int directorios;
    /** Suma de (bloques reservados - tamaño lógico) de los archivos regulares. */
    public final long fragmentacionInterna;

    InfoEspacio(int tamBloque, int totalBloques, int reservados, int ocupados, int libres,
                int inodosTotal, int inodosLibres, int archivos, int directorios, long frag) {
        this.tamBloque = tamBloque;
        this.totalBloques = totalBloques;
        this.reservadosMetadatos = reservados;
        this.datosOcupados = ocupados;
        this.datosLibres = libres;
        this.inodosTotal = inodosTotal;
        this.inodosLibres = inodosLibres;
        this.archivos = archivos;
        this.directorios = directorios;
        this.fragmentacionInterna = frag;
    }

    public long bytesTotales() { return (long) totalBloques * tamBloque; }
    public long bytesLibres() { return (long) datosLibres * tamBloque; }
    public long bytesOcupados() { return (long) datosOcupados * tamBloque; }
    public long bytesMetadatos() { return (long) reservadosMetadatos * tamBloque; }

    @Override
    public String toString() {
        return String.format(
                "Bloques de %d bytes | total: %d (%d B) | metadatos reservados: %d | datos ocupados: %d | datos libres: %d (%d B)%n"
                        + "Inodos: %d total, %d libres | archivos: %d, directorios: %d | fragmentacion interna: %d B%n"
                        + "Control: libres + ocupados = %d = %d - %d",
                tamBloque, totalBloques, bytesTotales(), reservadosMetadatos, datosOcupados, datosLibres, bytesLibres(),
                inodosTotal, inodosLibres, archivos, directorios, fragmentacionInterna,
                datosLibres + datosOcupados, totalBloques, reservadosMetadatos);
    }
}
