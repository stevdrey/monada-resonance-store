/**
 * JPMS consumer of the published Monada library artifacts.
 *
 * <p>The Monada jars are automatic modules with stable {@code Automatic-Module-Name}
 * entries. Only modules whose types appear in this consumer's code are required.
 */
module com.monada.consumer.jpms {
    requires com.monada.api;
    requires com.monada.core;
    requires com.monada.encoder;
    requires com.monada.storage;
}
