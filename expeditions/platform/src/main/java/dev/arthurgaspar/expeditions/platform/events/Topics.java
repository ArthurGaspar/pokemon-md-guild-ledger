package dev.arthurgaspar.expeditions.platform.events;

/** Kafka topics. Every record's key is the expedition id, so one expedition's events stay in order. */
public final class Topics {

    public static final String EXPEDITIONS = "expedition.events";
    public static final String RESERVATIONS = "reservation.events";
    public static final String RUNS = "run.events";
    public static final String ISSUANCE = "issuance.events";

    private Topics() {
    }
}
