package me.fy2ne.muto.engine.resource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ResourceHotMountTest {

    @Test
    void testRemountAndReloadHeadlessSafe() {
        Assertions.assertDoesNotThrow(() -> {
            CompletableFuture<Void> fut = ResourceHotMount.remountAndReload();
            Assertions.assertNotNull(fut);
            fut.get(5, TimeUnit.SECONDS);
            Assertions.assertTrue(fut.isDone());
        });
    }
}
