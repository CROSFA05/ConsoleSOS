package com.itescasos.IO;

public enum Direccion {
    ASCENDENTE, DESCENDENTE;

    public Direccion invertida() {
        return this == ASCENDENTE ? DESCENDENTE : ASCENDENTE;
    }
}
