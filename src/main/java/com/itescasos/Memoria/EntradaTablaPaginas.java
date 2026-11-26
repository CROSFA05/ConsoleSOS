package com.itescasos.Memoria;

//Representa cada fila de la tabla de páginas de un proceso, incluyendo 
// bits de control para reemplazo y validez.

public class EntradaTablaPaginas {
    public int numeroPagina;
    public int numeroMarco = -1;
    public boolean presente = false;
    public boolean bitReferencia = false;
    public long ultimoAcceso = 0;

    /** Bit de modificación (dirty): se activa cuando la página fue escrita. */
    public boolean bitModificacion = false;

    /** Orden de carga del marco asignado; -1 cuando la página no es residente. */
    public long ordenCarga = -1;

    public EntradaTablaPaginas(int numeroPagina) {
        this.numeroPagina = numeroPagina;
    }

    /** Registra la carga de la página en un marco junto con su orden de llegada. */
    public void registrarCarga(int numeroMarco, long instante, long ordenCarga) {
        this.numeroMarco = numeroMarco;
        this.presente = true;
        this.ultimoAcceso = instante;
        this.ordenCarga = ordenCarga;
    }

    /** Registra un acceso: bit de referencia, último uso y (si aplica) escritura. */
    public void registrarAcceso(boolean escritura, long instante) {
        this.bitReferencia = true;
        this.ultimoAcceso = instante;
        if (escritura) {
            this.bitModificacion = true;
        }
    }

    /** Marca la entrada como no residente y limpia sus bits de control. */
    public void liberar() {
        this.numeroMarco = -1;
        this.presente = false;
        this.bitReferencia = false;
        this.bitModificacion = false;
        this.ultimoAcceso = 0;
        this.ordenCarga = -1;
    }

    public boolean residenteEn(int marco) {
        return presente && numeroMarco == marco;
    }

    @Override
    public String toString() {
        return "EntradaTablaPaginas{pagina=" + numeroPagina + ", marco=" + numeroMarco
                + ", presente=" + presente + ", referencia=" + bitReferencia
                + ", modificacion=" + bitModificacion + ", ultimoAcceso=" + ultimoAcceso
                + ", ordenCarga=" + ordenCarga + "}";
    }
}
