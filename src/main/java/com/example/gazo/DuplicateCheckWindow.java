package com.example.gazo;

import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Vault 内の重複・類似画像チェック UI。ロジック本体は {@link DuplicateCheckSession}。
 */
public final class DuplicateCheckWindow {

    private DuplicateCheckWindow() {}

    public static void open(
            DuplicateCheckHost host,
            Set<Path> duplicateFocusForNextScan,
            Consumer<Stage> registerOpen,
            Runnable unregisterOnClose) {
        new DuplicateCheckSession(host).run(duplicateFocusForNextScan, registerOpen, unregisterOnClose);
    }
}
