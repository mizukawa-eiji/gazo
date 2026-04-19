package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;

import java.util.concurrent.CompletableFuture;

/**
 * Vault 解錠成功後、既存 Vault を閉じてから新しい {@link GazoVaultService} をアプリへ取り込む処理。
 * 非同期の取り込み（コピー移行など）では {@link CompletableFuture} を返す。
 */
@FunctionalInterface
public interface VaultUnlockSuccess {
    CompletableFuture<Void> adoptUnlockedVault(GazoVaultService newVault);
}
