package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 動画タブが参照する依存関係（Vault、タグ絞り込み、インポート時の UI）。
 */
public record VideoTabHost(
        GazoVaultService vault,
        Stage primaryStage,
        Supplier<Set<String>> activeTagFilters,
        Predicate<Path> isPendingDelete,
        Consumer<String> setImportStatusLabel,
        Runnable clearImportStatusLabel,
        Runnable refreshTagFilterOptions) {}
