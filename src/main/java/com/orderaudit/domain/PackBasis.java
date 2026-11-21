package com.orderaudit.domain;

/** How a packaging line scales: once per packet (pouch) or once per carton (carton). */
public enum PackBasis {
    PER_PACKET,
    PER_CARTON
}
