package io.vertx.mutiny.sqlclient;

import java.util.function.Function;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Utilities for generating {@link Multi} and {@link Uni} with a {@link SqlClient}.
 */
public class SqlClientHelper {

    /**
     * Generates a {@link Multi} from operations executed inside a {@link Transaction}.
     *
     * @param pool the {@link Pool}
     * @param sourceSupplier a user-provided function returning a {@link Multi} generated by interacting with the given
     *        {@link SqlClient}
     * @param <T> the type of the items emitted by the {@link Multi}
     * @return a {@link Multi} generated from operations executed inside a {@link Transaction}
     */
    public static <T> Multi<T> inTransactionMulti(Pool pool, Function<SqlClient, Multi<T>> sourceSupplier) {
        return usingConnectionMulti(pool, conn -> {
            Transaction transaction = conn.begin();
            return Multi.createBy().concatenating().streams(
                    sourceSupplier.apply(transaction),
                    transaction.commit().toMulti().onItem().transform(aVoid -> (T) aVoid))
                    .onFailure().recoverWithMulti(throwable -> {
                        return transaction.rollback().onFailure().recoverWithItem((Void) null)
                                .onItem().transformToMulti(v -> Multi.createFrom().failure(throwable));
                    });
        });
    }

    /**
     * Generates a {@link Uni} from operations executed inside a {@link Transaction}.
     *
     * @param pool the {@link Pool}
     * @param sourceSupplier a user-provided function returning a {@link Uni} generated by interacting with the given
     *        {@link SqlClient}
     * @param <T> the type of the items emitted by the {@link Uni}
     * @return a {@link Uni} generated from operations executed inside a {@link Transaction}
     */
    public static <T> Uni<T> inTransactionUni(Pool pool, Function<SqlClient, Uni<T>> sourceSupplier) {
        return usingConnectionUni(pool, conn -> {
            Transaction transaction = conn.begin();
            return sourceSupplier.apply(transaction)
                    .onItem()
                    .transformToUni(item -> transaction.commit().onItem().transformToUni(v -> Uni.createFrom().item(item)))
                    .onFailure().recoverWithUni(throwable -> {
                        return transaction.rollback().onFailure().recoverWithItem((Void) null)
                                .onItem().transformToUni(v -> Uni.createFrom().failure(throwable));
                    });
        });
    }

    /**
     * Generates a {@link Multi} from {@link SqlConnection} operations.
     *
     * @param pool the {@link Pool}
     * @param sourceSupplier a user-provided function returning a {@link Multi} generated by interacting with the given
     *        {@link SqlConnection}
     * @param <T> the type of the items emitted by the {@link Multi}
     * @return a {@link Multi} generated from {@link SqlConnection} operations
     */
    public static <T> Multi<T> usingConnectionMulti(Pool pool, Function<SqlConnection, Multi<T>> sourceSupplier) {
        return pool.getConnection().onItem().transformToMulti(conn -> {
            try {
                return sourceSupplier.apply(conn)
                        .onTermination().invoke(conn::close);
            } catch (Throwable t) {
                conn.close();
                return Multi.createFrom().failure(t);
            }
        });
    }

    /**
     * Generates a {@link Uni} from {@link SqlConnection} operations.
     *
     * @param pool the {@link Pool}
     * @param sourceSupplier a user-provided function returning a {@link Uni} generated by interacting with the given
     *        {@link SqlConnection}
     * @param <T> the type of the item emitted by the {@link Uni}
     * @return a {@link Uni} generated from {@link SqlConnection} operations
     */
    public static <T> Uni<T> usingConnectionUni(Pool pool, Function<SqlConnection, Uni<T>> sourceSupplier) {
        return pool.getConnection().onItem().transformToUni(conn -> {
            try {
                return sourceSupplier.apply(conn).onTermination().invoke(conn::close);
            } catch (Throwable t) {
                conn.close();
                return Uni.createFrom().failure(t);
            }
        });
    }

    private SqlClientHelper() {
        // Utility
    }
}
