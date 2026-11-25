package com.itescasos.Archivos;

public enum ModoApertura {
    LECTURA, ESCRITURA, LECTURA_ESCRITURA;

    public boolean puedeLeer() { return this != ESCRITURA; }

    public boolean puedeEscribir() { return this != LECTURA; }
}
