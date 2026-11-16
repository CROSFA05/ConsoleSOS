package com.itescasos.Memoria;

//Representa cada fila de la tabla de páginas de un proceso, incluyendo 
// bits de control para reemplazo y validez.

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
