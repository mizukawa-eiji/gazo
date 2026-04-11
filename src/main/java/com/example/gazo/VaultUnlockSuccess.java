package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;

/**
 * Vault 解錠成功後、既存 Vault を閉じてから新しい {@link GazoVaultService} をアプリへ取り込む処理。
 */
@FunctionalInterface
public interface VaultUnlockSuccess {
    void adoptUnlockedVault(GazoVaultService newVault) throws Exception;
}
