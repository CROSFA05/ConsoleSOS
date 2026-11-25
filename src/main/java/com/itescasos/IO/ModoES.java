package com.itescasos.IO;

/**
 * Contrato de la operación. SINCRONA: el solicitante se bloquea hasta el fin
 * (la CPU queda libre para otros). ASINCRONA: el solicitante sigue trabajando y
 * recoge el resultado después; solo se bloquea si lo necesita antes de que termine.
 */
public enum ModoES {
    SINCRONA, ASINCRONA
}
