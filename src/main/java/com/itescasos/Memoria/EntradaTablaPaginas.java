package com.itescasos.Memoria;

public class EntradaTablaPaginas {
    public int numeroPagina;
    public int numeroMarco = -1;
    public boolean presente = false;
    public boolean bitReferencia = false;
    public long ultimoAcceso = 0;

    public EntradaTablaPaginas(int numeroPagina) {
        this.numeroPagina = numeroPagina;
    }
}
