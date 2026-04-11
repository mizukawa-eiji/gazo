package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.scene.layout.FlowPane;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link VaultDnDImport} が Vault・ギャラリー見た目・インポート後の一覧更新にアクセスするための依存関係。
 */
public record ImportDropHost(
        Supplier<GazoVaultService> vault,
        FlowPane imageGalleryFlow,
        Supplier<FlowPane> videoGalleryFlow,
        Consumer<String> setImportStatusLabel,
        Runnable clearImportStatusLabel,
        Runnable onImportCompleteRefresh) {}
