package com.orderaudit.domain;

/**
 * Carton arithmetic for finished packets (e.g. 1 carton holds 20 packets).
 * Used to explain the benchmark carton gap: 188 packets need 10 cartons to box them all,
 * but 9 cartons hold only 180, leaving 8 loose packets.
 */
public record CartonPacking(long packets, int packetsPerCarton) {

    public CartonPacking {
        if (packets < 0) {
            throw new IllegalArgumentException("packets must not be negative");
        }
        if (packetsPerCarton <= 0) {
            throw new IllegalArgumentException("packetsPerCarton must be positive");
        }
    }

    public long fullCartons() {
        return packets / packetsPerCarton;
    }

    public long loosePackets() {
        return packets % packetsPerCarton;
    }

    /** Cartons needed to box every packet (rounds up). */
    public long cartonsNeededToBoxAll() {
        return (packets + packetsPerCarton - 1) / packetsPerCarton;
    }

    /** Packets left outside cartons if only this many cartons were used. */
    public long uncartonedPackets(long cartonsUsed) {
        return Math.max(0L, packets - Math.multiplyExact(cartonsUsed, (long) packetsPerCarton));
    }
}
